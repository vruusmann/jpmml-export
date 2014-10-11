/*
 * Copyright (c) 2014 Villu Ruusmann
 */
package org.jpmml.export;

import java.util.ArrayList;
import java.util.List;

import rexp.Rexp;
import rexp.Rexp.STRING;

public class REXPUtil {

	private REXPUtil(){
	}

	static
	public boolean inherits(Rexp.REXP rexp, String name){
		Rexp.REXP clazz = REXPUtil.attribute(rexp, "class");

		for(int i = 0; i < clazz.getStringValueCount(); i++){
			STRING clazzValue = clazz.getStringValue(i);

			if((name).equals(clazzValue.getStrval())){
				return true;
			}
		}

		return false;
	}

	static
	public Rexp.REXP field(Rexp.REXP rexp, String name){
		Rexp.REXP names = attribute(rexp, "names");

		List<String> fields = new ArrayList<String>();

		for(int i = 0; i < names.getStringValueCount(); i++){
			STRING nameValue = names.getStringValue(i);

			if((name).equals(nameValue.getStrval())){
				return rexp.getRexpValue(i);
			}

			fields.add(nameValue.getStrval());
		}

		throw new IllegalArgumentException("Field " + name + " not in " + fields);
	}

	static
	public Rexp.REXP attribute(Rexp.REXP rexp, String name){
		List<String> attributes = new ArrayList<String>();

		for(int i = 0; i < rexp.getAttrNameCount(); i++){

			if((rexp.getAttrName(i)).equals(name)){
				return rexp.getAttrValue(i);
			}

			attributes.add(rexp.getAttrName(i));
		}

		throw new IllegalArgumentException("Attribute " + name + " not in " + attributes);
	}
}