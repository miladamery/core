package org.ow2.authzforce.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.sf.saxon.s9api.XPathCompiler;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.AttributeAssignment;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.AttributeAssignmentExpression;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.EffectType;

import org.ow2.authzforce.core.expression.ExpressionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.xacml.ParsingException;

/**
 * PEP action (obligation/advice) expression evaluator
 *
 * @param <JAXB_PEP_ACTION>
 *            PEP action type in XACML/JAXB model (Obligation/Advice)
 */
public final class PepActionExpression<JAXB_PEP_ACTION>
{
	private static final Logger LOGGER = LoggerFactory.getLogger(PepActionExpression.class);

	private String actionId;
	private final transient JAXB_PEP_ACTION emptyPepAction;
	private final transient List<AttributeAssignmentExpressionEvaluator> evaluatableAttributeAssignmentExpressions;

	private final PepActionFactory<JAXB_PEP_ACTION> pepActionFactory;

	private final String infoPrefix;

	private final EffectType appliesTo;

	/**
	 * Constructor that takes all the data associated with an PEP action (obligation/advice) expression.
	 * 
	 * @param pepActionFactory
	 *            PEP action factory
	 * 
	 * @param pepActionId
	 *            the obligation's id
	 * @param appliesTo
	 *            the type of decision to which the PEP action applies (ObligationExpression's FulfillOn / AdviceExpression's AppliesTo)
	 * @param jaxbAssignmentExps
	 *            a <code>List</code> of <code>AttributeAssignmentExpression</code>s
	 * @param xPathCompiler
	 *            XPath compiler corresponding to enclosing policy(set) default XPath version
	 * @param expFactory
	 *            Expression factory for parsing/instantiating AttributeAssignment expressions
	 * @throws ParsingException
	 *             error parsing one of the AttributeAssignmentExpressions' Expression
	 */
	public PepActionExpression(PepActionFactory<JAXB_PEP_ACTION> pepActionFactory, String pepActionId, EffectType appliesTo,
			List<AttributeAssignmentExpression> jaxbAssignmentExps, XPathCompiler xPathCompiler, ExpressionFactory expFactory) throws ParsingException
	{
		this.actionId = pepActionId;
		this.appliesTo = appliesTo;

		if (jaxbAssignmentExps == null || jaxbAssignmentExps.isEmpty())
		{
			emptyPepAction = pepActionFactory.getInstance(null, pepActionId);
			this.evaluatableAttributeAssignmentExpressions = Collections.emptyList();
		} else
		{
			emptyPepAction = null;
			this.evaluatableAttributeAssignmentExpressions = new ArrayList<>(jaxbAssignmentExps.size());
			for (final AttributeAssignmentExpression jaxbAttrAssignExp : jaxbAssignmentExps)
			{
				final AttributeAssignmentExpressionEvaluator attrAssignExp;
				try
				{
					attrAssignExp = new AttributeAssignmentExpressionEvaluator(jaxbAttrAssignExp, xPathCompiler, expFactory);
				} catch (ParsingException e)
				{
					throw new ParsingException("Error parsing AttributeAssignmentExpression[@AttributeId=" + jaxbAttrAssignExp.getAttributeId()
							+ "]/Expression", e);
				}

				this.evaluatableAttributeAssignmentExpressions.add(attrAssignExp);
			}
		}

		this.pepActionFactory = pepActionFactory;
		this.infoPrefix = pepActionFactory.getActionXmlElementName() + "Expression[@" + pepActionFactory.getActionXmlElementName() + "=" + actionId + "]";
	}

	/**
	 * The type of decision to which the PEP action applies (ObligationExpression's FulfillOn / AdviceExpression's AppliesTo)
	 * 
	 * @return appliesTo/fulfillOn property
	 */
	public EffectType getAppliesTo()
	{
		return appliesTo;
	}

	/**
	 * ID of the PEP action (ObligationId / AdviceId)
	 * 
	 * @return action ID
	 */
	public String getActionId()
	{
		return this.actionId;
	}

	/**
	 * Evaluates to a PEP action (obligation/advice).
	 * 
	 * @param context
	 *            evaluation context
	 * 
	 * @return an instance of a PEP action in JAXB model (JAXB Obligation/Advice)
	 * 
	 * @throws IndeterminateEvaluationException
	 *             if any of the attribute assignment expressions evaluates to "Indeterminate" (see XACML 3.0 core spec, section 7.18)
	 */
	public JAXB_PEP_ACTION evaluate(EvaluationContext context) throws IndeterminateEvaluationException
	{
		// if no assignmentExpression
		if (this.emptyPepAction != null)
		{
			return this.emptyPepAction;
		}

		// else there are assignmentExpressions
		final List<AttributeAssignment> assignments = new ArrayList<>();
		for (final AttributeAssignmentExpressionEvaluator attrAssignmentExpr : this.evaluatableAttributeAssignmentExpressions)
		{
			/*
			 * Section 5.39 of XACML 3.0 core spec says there may be multiple AttributeAssignments resulting from one AttributeAssignmentExpression
			 */
			final List<AttributeAssignment> attrAssignsFromExpr;
			try
			{
				attrAssignsFromExpr = attrAssignmentExpr.evaluate(context);
				LOGGER.debug("{}/{} -> {}", this.infoPrefix, attrAssignmentExpr, attrAssignsFromExpr);
			} catch (IndeterminateEvaluationException e)
			{
				throw new IndeterminateEvaluationException(infoPrefix + ": Error evaluating AttributeAssignmentExpression[@AttributeId="
						+ attrAssignmentExpr.getAttributeId() + "]/Expression", e.getStatusCode(), e);
			}

			if (attrAssignsFromExpr != null)
			{
				assignments.addAll(attrAssignsFromExpr);
			}
		}

		return pepActionFactory.getInstance(assignments, actionId);
	}
}