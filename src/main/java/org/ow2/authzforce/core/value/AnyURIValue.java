/**
 * Copyright (C) 2011-2015 Thales Services SAS.
 *
 * This file is part of AuthZForce.
 *
 * AuthZForce is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * AuthZForce is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AuthZForce. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ow2.authzforce.core.value;

import net.sf.saxon.lib.StandardURIChecker;

/**
 * Represent the URI value that this class represents
 * <p>
 * WARNING: java.net.URI cannot be used here for this XACML datatype, because not equivalent to XML schema anyURI type. Spaces are allowed in XSD anyURI [1],
 * not in java.net.URI.
 * </p>
 * <p>
 * [1] http://www.w3.org/TR/xmlschema-2/#anyURI That's why we use String instead.
 * </p>
 * <p>
 * See also:
 * </p>
 * <p>
 * https://java.net/projects/jaxb/lists/users/archive/2011-07/message/16
 * </p>
 * <p>
 * From the JAXB spec: "xs:anyURI is not bound to java.net.URI by default since not all possible values of xs:anyURI can be passed to the java.net.URI
 * constructor. Using a global JAXB customization described in Section 7.9".
 * </p>
 */
public final class AnyURIValue extends SimpleValue<String>
{

	/**
	 * Official name of this type
	 */
	public static final String TYPE_URI = "http://www.w3.org/2001/XMLSchema#anyURI";

	/**
	 * Creates a new <code>AnyURIAttributeValue</code> that represents the URI value supplied.
	 * 
	 * @param value
	 *            the URI to be represented
	 *            <p>
	 *            WARNING: java.net.URI cannot be used here for XACML datatype, because not equivalent to XML schema anyURI type. Spaces are allowed in XSD
	 *            anyURI [1], not in java.net.URI. [1] http://www.w3.org/TR/xmlschema-2/#anyURI So we use String instead.
	 *            </p>
	 * @throws IllegalArgumentException
	 *             if {@code value} is not a valid string representation for xs:anyURI
	 */
	public AnyURIValue(String value) throws IllegalArgumentException
	{
		super(TYPE_URI, validate(value));
	}

	private static String validate(String anyURI) throws IllegalArgumentException
	{
		/*
		 * Please note that StandardURIChecker maintains a thread-local cache of validated URIs (cache size is 50 and eviction policy is LRU)
		 */
		if (!StandardURIChecker.getInstance().isValidURI(anyURI))
		{
			throw new IllegalArgumentException("Invalid value for xs:anyURI: " + anyURI);
		}

		return anyURI;
	}

	@Override
	public String printXML()
	{
		return this.value;
	}

	// /**
	// * For testing only
	// * @param args
	// */
	// public static void main(String... args) {
	// String values[] = {"http://localhost.example.com:9090/path/to/something/somewhere/close",
	// "http://com.example.localhost:7171/close/to/somewhere/something/path"};
	// long best = -1;
	// for(int i=0; i< 10000; i++) {
	// long start = System.nanoTime();
	// String result = String.format(XML_FRAGMENT_FORMAT, values[i%2]);
	// long elapsed = System.nanoTime() - start;
	// if(best == -1 || elapsed < best) {
	// best = elapsed;
	// }
	// }
	//
	// System.out.println("Best time with String.format(): " + best + " ns");
	//
	// best = -1;
	// for(int i=0; i< 10000; i++) {
	// long start = System.nanoTime();
	// //String result = String.format("<xml-fragment>%s</xml-fragment>", value);
	// String result =XML_FRAGMENT_START+values[i%2]+XML_FRAGMENT_END;
	// long elapsed = System.nanoTime() - start;
	// if(best == -1 || elapsed < best) {
	// best = elapsed;
	// }
	// }
	//
	// System.out.println("Best time with String +: " + best + " ns");
	// }

}