/**
 * Copyright (C) 2012-2015 Thales Services SAS.
 *
 * This file is part of AuthZForce CE.
 *
 * AuthZForce CE is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * AuthZForce CE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AuthZForce CE. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ow2.authzforce.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.datatype.XMLGregorianCalendar;

import oasis.names.tc.xacml._3_0.core.schema.wd_17.DecisionType;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Request;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Response;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Result;

import org.ow2.authzforce.core.combining.CombiningAlgRegistry;
import org.ow2.authzforce.core.expression.AttributeGUID;
import org.ow2.authzforce.core.func.FunctionRegistry;
import org.ow2.authzforce.core.policy.RootPolicyEvaluator;
import org.ow2.authzforce.core.value.Bag;
import org.ow2.authzforce.core.value.Bags;
import org.ow2.authzforce.core.value.DatatypeConstants;
import org.ow2.authzforce.core.value.DatatypeFactoryRegistry;
import org.ow2.authzforce.core.value.DateTimeValue;
import org.ow2.authzforce.core.value.DateValue;
import org.ow2.authzforce.core.value.TimeValue;
import org.ow2.authzforce.xacml.identifiers.XACMLAttributeId;
import org.ow2.authzforce.xacml.identifiers.XACMLCategory;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractAttributeProvider;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractDecisionCache;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractPolicyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.xacml.PDP;

/**
 * This is the core XACML PDP engine implementation, providing the starting point for request evaluation. To build an XACML policy engine, you start by
 * instantiating this object directly or in a easier and preferred way, using {@link PdpConfigurationParser}.
 * <p>
 * This class implements {@link Closeable} because it depends on various modules - e.g. the root policy Provider, an optional decision cache - that may very
 * likely hold resources such as network resources and caches to get: the root policy or policies referenced by the root policy; or to get attributes used in
 * the policies from remote sources when not provided in the Request; or to get cached decisions for requests already evaluated in the past, etc. Therefore, you
 * are required to call {@link #close()} when you no longer need an instance - especially before replacing with a new instance - in order to make sure these
 * resources are released properly by each underlying module (e.g. invalidate the attribute caches and/or network resources).
 * 
 */
public class PDPImpl implements PDP, Closeable
{
	// the logger we'll use for all messages
	private static final Logger LOGGER = LoggerFactory.getLogger(PDPImpl.class);

	/**
	 * Indeterminate response iff CombinedDecision element not supported because the request parser does not support any scheme from MultipleDecisionProfile
	 * section 2.
	 */
	private static final Response UNSUPPORTED_COMBINED_DECISION_RESPONSE = new Response(Collections.<Result> singletonList(new Result(
			DecisionType.INDETERMINATE, new StatusHelper(StatusHelper.STATUS_SYNTAX_ERROR, "Unsupported feature: CombinedDecision='true'"), null, null, null,
			null)));

	private static final AttributeGUID ENVIRONMENT_CURRENT_TIME_ATTRIBUTE_GUID = new AttributeGUID(
			XACMLCategory.XACML_3_0_ENVIRONMENT_CATEGORY_ENVIRONMENT.value(), null, XACMLAttributeId.XACML_1_0_ENVIRONMENT_CURRENT_TIME.value());

	private static final AttributeGUID ENVIRONMENT_CURRENT_DATE_ATTRIBUTE_GUID = new AttributeGUID(
			XACMLCategory.XACML_3_0_ENVIRONMENT_CATEGORY_ENVIRONMENT.value(), null, XACMLAttributeId.XACML_1_0_ENVIRONMENT_CURRENT_DATE.value());

	private static final AttributeGUID ENVIRONMENT_CURRENT_DATETIME_ATTRIBUTE_GUID = new AttributeGUID(
			XACMLCategory.XACML_3_0_ENVIRONMENT_CATEGORY_ENVIRONMENT.value(), null, XACMLAttributeId.XACML_1_0_ENVIRONMENT_CURRENT_DATETIME.value());

	private static final DecisionResultFilter DEFAULT_RESULT_FILTER = new DecisionResultFilter()
	{
		private static final String ID = "urn:thalesgroup:xacml:result-filter:default";

		@Override
		public String getId()
		{
			return ID;
		}

		@Override
		public List<Result> filter(List<Result> results)
		{
			return results;
		}

		@Override
		public boolean supportsMultipleDecisionCombining()
		{
			return false;
		}

	};

	private final RootPolicyEvaluator rootPolicyProvider;
	private final DecisionCache decisionCache;
	private final RequestFilter reqFilter;
	private final IndividualDecisionRequestEvaluator individualReqEvaluator;
	private final DecisionResultFilter resultFilter;

	private static class CachingIndividualRequestEvaluator extends IndividualDecisionRequestEvaluator
	{
		// the logger we'll use for all messages
		private static final Logger _LOGGER = LoggerFactory.getLogger(CachingIndividualRequestEvaluator.class);

		private static final Result INVALID_DECISION_CACHE_RESULT = new Result(DecisionType.INDETERMINATE, new StatusHelper(
				StatusHelper.STATUS_PROCESSING_ERROR, "Internal error"), null, null, null, null);

		private final DecisionCache decisionCache;

		private CachingIndividualRequestEvaluator(RootPolicyEvaluator rootPolicyEvaluator, DecisionCache decisionCache)
		{
			super(rootPolicyEvaluator);
			assert decisionCache != null;
			this.decisionCache = decisionCache;
		}

		@Override
		public final List<Result> evaluate(List<? extends IndividualDecisionRequest> individualDecisionRequests, Map<AttributeGUID, Bag<?>> pdpIssuedAttributes)
		{
			final Map<IndividualDecisionRequest, Result> cachedResultsByRequest = decisionCache.getAll(individualDecisionRequests);
			if (cachedResultsByRequest == null)
			{
				// error, return indeterminate result as only result
				_LOGGER.error("Invalid decision cache result: null");
				return Collections.singletonList(INVALID_DECISION_CACHE_RESULT);
			}

			// At least check that we have as many results from cache as input requests
			// (For each request with no result in cache, there must still be an entry with value
			// null.)
			if (cachedResultsByRequest.size() != individualDecisionRequests.size())
			{
				// error, return indeterminate result as only result
				_LOGGER.error("Invalid decision cache result: number of returned decision results ({}) != number of input (individual) decision requests ({})",
						cachedResultsByRequest.size(), individualDecisionRequests.size());
				return Collections.singletonList(INVALID_DECISION_CACHE_RESULT);
			}

			final Set<Entry<IndividualDecisionRequest, Result>> cachedRequestResultEntries = cachedResultsByRequest.entrySet();
			final List<Result> results = new ArrayList<>(cachedRequestResultEntries.size());
			final Map<IndividualDecisionRequest, Result> newResultsByRequest = new HashMap<>();
			for (final Entry<IndividualDecisionRequest, Result> cachedRequestResultPair : cachedRequestResultEntries)
			{
				final Result finalResult;
				final Result cachedResult = cachedRequestResultPair.getValue();
				if (cachedResult == null)
				{
					// result not in cache -> evaluate request
					final IndividualDecisionRequest individuaDecisionRequest = cachedRequestResultPair.getKey();
					finalResult = super.evaluate(individuaDecisionRequest, pdpIssuedAttributes);
					newResultsByRequest.put(individuaDecisionRequest, finalResult);
				} else
				{
					finalResult = cachedResult;
				}

				results.add(finalResult);
			}

			decisionCache.putAll(newResultsByRequest);
			return results;
		}
	}

	/**
	 * Constructs a new <code>PDP</code> object with the given configuration information.
	 * 
	 * @param attributeFactory
	 *            attribute value factory - mandatory
	 * @param functionRegistry
	 *            function registry - mandatory
	 * @param jaxbAttributeProviderConfs
	 *            XML/JAXB configurations of Attribute Providers for AttributeDesignator/AttributeSelector evaluation; may be null for static expression
	 *            evaluation (out of context), in which case AttributeSelectors/AttributeDesignators are not supported
	 * @param maxVariableReferenceDepth
	 *            max depth of VariableReference chaining: VariableDefinition -> VariableDefinition ->... ('->' represents a VariableReference)
	 * @param enableXPath
	 *            allow XPath evaluation, i.e. AttributeSelectors and xpathExpressions (experimental, not for production, use with caution)
	 * @param requestFilterId
	 *            ID of request filter (XACML Request processing prior to policy evaluation) - mandatory
	 * @param decisionResultFilter
	 *            decision result filter (XACML Result processing after policy evaluation, before creating/returning final XACML Response)
	 * @param jaxbDecisionCacheConf
	 *            decision response cache XML/JAXB configuration
	 * @param jaxbRootPolicyProviderConf
	 *            root policy Provider's XML/JAXB configuration - mandatory
	 * @param combiningAlgRegistry
	 *            XACML policy/rule combining algorithm registry - mandatory
	 * @param jaxbRefPolicyProviderConf
	 *            policy-by-reference Provider's XML/JAXB configuration, for resolving policies referred to by Policy(Set)IdReference in policies found by root
	 *            policy Provider
	 * @param maxPolicySetRefDepth
	 *            max allowed PolicySetIdReference chain: PolicySet1 (PolicySetIdRef1) -> PolicySet2 (PolicySetIdRef2) -> ...
	 * @param strictAttributeIssuerMatch
	 *            true iff strict Attribute Issuer matching is enabled, i.e. AttributeDesignators without Issuer only match request Attributes without Issuer
	 *            (and same AttributeId, Category...). This mode is not fully compliant with XACML 3.0, §5.29, in the case that the Issuer is indeed not present
	 *            on a AttributeDesignator; but it performs better and is recommended when all AttributeDesignators have an Issuer (best practice). Reminder:
	 *            the XACML 3.0 specification for AttributeDesignator evaluation (5.29) says: "If the Issuer is not present in the attribute designator, then
	 *            the matching of the attribute to the named attribute SHALL be governed by AttributeId and DataType attributes alone." if one of the mandatory
	 *            arguments is null
	 * @throws IllegalArgumentException
	 *             if there is not any extension found for type {@link org.ow2.authzforce.core.RequestFilter.Factory} with ID {@code requestFilterId}; or if one
	 *             of the mandatory arguments is null; or if any Attribute Provider module created from {@code jaxbAttributeProviderConfs} does not provide any
	 *             attribute; or it is in conflict with another one already registered to provide the same or part of the same attributes; of if there is no
	 *             extension supporting {@code jaxbDecisionCacheConf}
	 * 
	 * @throws IOException
	 *             error closing the root policy Provider when static resolution is to be used; or error closing the attribute Provider modules created from
	 *             {@code jaxbAttributeProviderConfs}, when and before an {@link IllegalArgumentException} is raised
	 * 
	 */
	public PDPImpl(DatatypeFactoryRegistry attributeFactory, FunctionRegistry functionRegistry, List<AbstractAttributeProvider> jaxbAttributeProviderConfs,
			int maxVariableReferenceDepth, boolean enableXPath, CombiningAlgRegistry combiningAlgRegistry, AbstractPolicyProvider jaxbRootPolicyProviderConf,
			AbstractPolicyProvider jaxbRefPolicyProviderConf, int maxPolicySetRefDepth, String requestFilterId, boolean strictAttributeIssuerMatch,
			DecisionResultFilter decisionResultFilter, AbstractDecisionCache jaxbDecisionCacheConf) throws IllegalArgumentException, IOException
	{
		final RequestFilter.Factory requestFilterFactory = requestFilterId == null ? DefaultRequestFilter.LaxFilterFactory.INSTANCE : PdpExtensionLoader
				.getExtension(RequestFilter.Factory.class, requestFilterId);

		final RequestFilter requestFilter = requestFilterFactory.getInstance(attributeFactory, strictAttributeIssuerMatch, enableXPath,
				XACMLParsers.SAXON_PROCESSOR);

		final RootPolicyEvaluator.Base candidateRootPolicyProvider = new RootPolicyEvaluator.Base(attributeFactory, functionRegistry,
				jaxbAttributeProviderConfs, maxVariableReferenceDepth, enableXPath, combiningAlgRegistry, jaxbRootPolicyProviderConf,
				jaxbRefPolicyProviderConf, maxPolicySetRefDepth, strictAttributeIssuerMatch);
		// Use static resolution if possible
		final RootPolicyEvaluator staticRootPolicyProvider = candidateRootPolicyProvider.toStatic();
		if (staticRootPolicyProvider == null)
		{
			this.rootPolicyProvider = candidateRootPolicyProvider;
		} else
		{
			this.rootPolicyProvider = staticRootPolicyProvider;
		}

		this.reqFilter = requestFilter;

		// decision cache
		if (jaxbDecisionCacheConf == null)
		{
			this.decisionCache = null;
		} else
		{
			final DecisionCache.Factory<AbstractDecisionCache> responseCacheStoreFactory = PdpExtensionLoader.getJaxbBoundExtension(
					DecisionCache.Factory.class, jaxbDecisionCacheConf.getClass());
			this.decisionCache = responseCacheStoreFactory.getInstance(jaxbDecisionCacheConf);
		}

		this.individualReqEvaluator = this.decisionCache == null ? new IndividualDecisionRequestEvaluator(rootPolicyProvider)
				: new CachingIndividualRequestEvaluator(rootPolicyProvider, this.decisionCache);
		this.resultFilter = decisionResultFilter == null ? DEFAULT_RESULT_FILTER : decisionResultFilter;
	}

	@Override
	public Response evaluate(Request request, Map<String, String> namespaceURIsByPrefix)
	{
		/*
		 * No support for CombinedDecision = true if no decisionCombiner defined. (The use of the CombinedDecision attribute is specified in Multiple Decision
		 * Profile.)
		 */
		if (request.isCombinedDecision() && !resultFilter.supportsMultipleDecisionCombining())
		{
			/*
			 * According to XACML core spec, 5.42, "If the PDP does not implement the relevant functionality in [Multiple Decision Profile], then the PDP must
			 * return an Indeterminate with a status code of urn:oasis:names:tc:xacml:1.0:status:processing-error if it receives a request with this attribute
			 * set to �true�.
			 */
			return UNSUPPORTED_COMBINED_DECISION_RESPONSE;
		}

		/*
		 * The request parser may return multiple individual decision requests from a single Request, e.g. if the request parser implements the Multiple
		 * Decision profile or Hierarchical Resource profile
		 */
		final List<? extends IndividualDecisionRequest> individualDecisionRequests;
		try
		{
			individualDecisionRequests = reqFilter.filter(request, namespaceURIsByPrefix);
		} catch (IndeterminateEvaluationException e)
		{
			LOGGER.info("Invalid or unsupported input XACML Request syntax", e);
			return new Response(Collections.<Result> singletonList(new Result(DecisionType.INDETERMINATE, e.getStatus(), null, null, null, null)));
		}
		/*
		 * Every request context (named attributes) is completed with common current date/time attribute (same values) set/"issued" locally (here by the PDP
		 * engine) according to XACML core spec:
		 * "This identifier indicates the current time at the context handler. In practice it is the time at which the request context was created." (� B.7).
		 */
		final Map<AttributeGUID, Bag<?>> pdpIssuedAttributes = new HashMap<>();
		// current datetime
		final DateTimeValue currentDateTimeValue = new DateTimeValue(new GregorianCalendar());
		pdpIssuedAttributes.put(ENVIRONMENT_CURRENT_DATETIME_ATTRIBUTE_GUID, Bags.singleton(DatatypeConstants.DATETIME.TYPE, currentDateTimeValue));
		// current date
		pdpIssuedAttributes.put(ENVIRONMENT_CURRENT_DATE_ATTRIBUTE_GUID,
				Bags.singleton(DatatypeConstants.DATE.TYPE, DateValue.getInstance((XMLGregorianCalendar) currentDateTimeValue.getUnderlyingValue().clone())));
		// current time
		pdpIssuedAttributes.put(ENVIRONMENT_CURRENT_TIME_ATTRIBUTE_GUID,
				Bags.singleton(DatatypeConstants.TIME.TYPE, TimeValue.getInstance((XMLGregorianCalendar) currentDateTimeValue.getUnderlyingValue().clone())));

		// evaluate the individual decision requests with the extra common attributes set previously
		final List<Result> results = individualReqEvaluator.evaluate(individualDecisionRequests, pdpIssuedAttributes);
		final List<Result> filteredResults = resultFilter.filter(results);
		return new Response(filteredResults);
	}

	@Override
	public void close() throws IOException
	{
		rootPolicyProvider.close();
		if (decisionCache != null)
		{
			decisionCache.close();
		}
	}

	@Override
	public Response evaluate(Request request)
	{
		return evaluate(request, null);
	}

}