/*
 * Copyright (c) 2014 Villu Ruusmann
 */
package org.jpmml.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.transform.stream.StreamResult;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.protobuf.CodedInputStream;
import org.dmg.pmml.PMML;
import org.jpmml.model.JAXBUtil;
import rexp.Rexp;

public class Main {

	@Parameter (
		names = "--pb-file",
		description = "ProtoBuf input file",
		required = true
	)
	private File input = null;

	@Parameter (
		names = "--pmml-file",
		description = "PMML output file",
		required = true
	)
	private File output = null;


	static
	public void main(String... args) throws Exception {
		Main main = new Main();

		JCommander commander = new JCommander(main);
		commander.setProgramName(Main.class.getName());

		try {
			commander.parse(args);
		} catch(ParameterException pe){
			commander.usage();

			System.exit(-1);
		}

		main.run();
	}

	public void run() throws Exception {
		Rexp.REXP rexp;

		InputStream is = new FileInputStream(this.input);

		try {
			System.out.println("Parsing..");

			CodedInputStream cis = CodedInputStream.newInstance(is);
			cis.setSizeLimit(Integer.MAX_VALUE);

			long start = System.currentTimeMillis();
			rexp = Rexp.REXP.parseFrom(cis);
			long end = System.currentTimeMillis();

			System.out.println("Parsed ProtoBuf in " + (end - start) + " ms.");
		} finally {
			is.close();
		}

		PMML pmml = convert(rexp);

		OutputStream os = new FileOutputStream(this.output);

		try {
			System.out.println("Marshalling..");

			long start = System.currentTimeMillis();
			JAXBUtil.marshalPMML(pmml, new StreamResult(os));
			long end = System.currentTimeMillis();

			System.out.println("Marshalled PMML in " + (end - start) + " ms.");
		} finally {
			os.close();
		}
	}

	private PMML convert(Rexp.REXP rexp){
		Converter converter;

		if(REXPUtil.inherits(rexp, "kmeans")){
			converter = new KMeansConverter();
		} else

		if(REXPUtil.inherits(rexp, "randomForest")){
			converter = new RandomForestConverter();
		} else

		{
			throw new IllegalArgumentException();
		}

		return converter.convert(rexp);
	}

	public File getInput(){
		return this.input;
	}

	public void setInput(File input){

		if(input == null){
			throw new NullPointerException();
		}

		this.input = input;
	}

	public File getOutput(){
		return this.output;
	}

	public void setOutput(File output){

		if(output == null){
			throw new NullPointerException();
		}

		this.output = output;
	}
}