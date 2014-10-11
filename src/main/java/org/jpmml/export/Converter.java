/*
 * Copyright (c) 2014 Villu Ruusmann
 */
package org.jpmml.export;

import com.google.common.math.DoubleMath;
import org.dmg.pmml.PMML;
import rexp.Rexp;

abstract
public class Converter {

	abstract
	public PMML convert(Rexp.REXP rexp);

	static
	public String formatValue(Number number){
		double value = number.doubleValue();

		if(DoubleMath.isMathematicalInteger(value)){
			return Long.toString(number.longValue());
		}

		return Double.toString(value);
	}

	static
	public Integer asInteger(Number number){

		if(number instanceof Integer){
			return (Integer)number;
		}

		double value = number.doubleValue();

		if(DoubleMath.isMathematicalInteger(value)){
			return number.intValue();
		}

		throw new IllegalArgumentException();
	}

	static
	public Double asDouble(Number number){

		if(number instanceof Double){
			return (Double)number;
		}

		return number.doubleValue();
	}
}