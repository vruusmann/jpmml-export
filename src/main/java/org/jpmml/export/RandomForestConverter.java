/*
 * Copyright (c) 2014 Villu Ruusmann
 */
package org.jpmml.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.transform.stream.StreamResult;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import com.google.protobuf.CodedInputStream;
import org.dmg.pmml.AbstractVisitor;
import org.dmg.pmml.Array;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldUsageType;
import org.dmg.pmml.Header;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.MiningModel;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.MultipleModelMethodType;
import org.dmg.pmml.Node;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.Segment;
import org.dmg.pmml.Segmentation;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.SimpleSetPredicate;
import org.dmg.pmml.TreeModel;
import org.dmg.pmml.True;
import org.dmg.pmml.Value;
import org.dmg.pmml.VisitorAction;
import org.jpmml.model.JAXBUtil;
import rexp.Rexp;
import rexp.Rexp.STRING;

public class RandomForestConverter {

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

	private List<DataField> dataFields = new ArrayList<DataField>();

	private LoadingCache<PredicateKey, SimpleSetPredicate> leftSimpleSetPredicates = CacheBuilder.newBuilder()
		.build(new CacheLoader<PredicateKey, SimpleSetPredicate>(){

			@Override
			public SimpleSetPredicate load(PredicateKey key){
				return encodeSimpleSetPredicate(key.getDataField(), asInteger(key.getSplit()), true);
			}
		});

	private LoadingCache<PredicateKey, SimpleSetPredicate> rightSimpleSetPredicates = CacheBuilder.newBuilder()
		.build(new CacheLoader<PredicateKey, SimpleSetPredicate>(){

			@Override
			public SimpleSetPredicate load(PredicateKey key){
				return encodeSimpleSetPredicate(key.getDataField(), asInteger(key.getSplit()), false);
			}
		});

	private LoadingCache<PredicateKey, SimplePredicate> leftSimplePredicates = CacheBuilder.newBuilder()
		.build(new CacheLoader<PredicateKey, SimplePredicate>(){

			@Override
			public SimplePredicate load(PredicateKey key){
				return encodeSimplePredicate(key.getDataField(), asDouble(key.getSplit()), true);
			}
		});

	private LoadingCache<PredicateKey, SimplePredicate> rightSimplePredicates = CacheBuilder.newBuilder()
		.build(new CacheLoader<PredicateKey, SimplePredicate>(){

			@Override
			public SimplePredicate load(PredicateKey key){
				return encodeSimplePredicate(key.getDataField(), asDouble(key.getSplit()), false);
			}
		});


	static
	public void main(String[] args) throws Exception {
		RandomForestConverter converter = new RandomForestConverter();

		JCommander commander = new JCommander(converter);
		commander.setProgramName(RandomForestConverter.class.getName());

		try {
			commander.parse(args);
		} catch(ParameterException pe){
			commander.usage();

			System.exit(-1);
		}

		converter.run();
	}

	private RandomForestConverter(){
	}

	public void run() throws Exception {
		this.dataFields.clear();

		Rexp.REXP randomForest;

		InputStream is = new FileInputStream(this.input);

		try {
			System.out.println("Parsing..");

			CodedInputStream cis = CodedInputStream.newInstance(is);
			cis.setSizeLimit(Integer.MAX_VALUE);

			long start = System.currentTimeMillis();
			randomForest = Rexp.REXP.parseFrom(cis);
			long end = System.currentTimeMillis();

			System.out.println("Parsed ProtoBuf in " + (end - start) + " ms.");
		} finally {
			is.close();
		}

		PMML pmml = convert(randomForest);

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

	private PMML convert(Rexp.REXP randomForest){
		Rexp.REXP type = field(randomForest, "type");
		Rexp.REXP forest = field(randomForest, "forest");

		try {
			Rexp.REXP terms = field(randomForest, "terms");

			// The RF model was trained using the formula interface
			initFormulaFields(terms);
		} catch(IllegalArgumentException iae){
			Rexp.REXP xlevels = field(forest, "xlevels");
			Rexp.REXP ncat = field(forest, "ncat");

			Rexp.REXP y;

			try {
				y = field(randomForest, "y");
			} catch(IllegalArgumentException iaeChild){
				y = null;
			}

			// The RF model was trained using the matrix (ie. non-formula) interface
			initNonFormulaFields(xlevels, ncat, y);
		}

		STRING typeValue = type.getStringValue(0);

		if("regression".equals(typeValue.getStrval())){
			return convertRegression(forest);
		} else

		if("classification".equals(typeValue.getStrval())){
			Rexp.REXP y = field(randomForest, "y");

			return convertClassification(forest, y);
		} else

		{
			throw new IllegalArgumentException();
		}
	}

	private PMML convertRegression(Rexp.REXP forest){
		Rexp.REXP leftDaughter = field(forest, "leftDaughter");
		Rexp.REXP rightDaughter = field(forest, "rightDaughter");
		Rexp.REXP nodepred = field(forest, "nodepred");
		Rexp.REXP bestvar = field(forest, "bestvar");
		Rexp.REXP xbestsplit = field(forest, "xbestsplit");
		Rexp.REXP ncat = field(forest, "ncat");
		Rexp.REXP nrnodes = field(forest, "nrnodes");
		Rexp.REXP ntree = field(forest, "ntree");
		Rexp.REXP xlevels = field(forest, "xlevels");

		initActiveFields(xlevels, ncat);

		ScoreEncoder<Double> scoreEncoder = new ScoreEncoder<Double>(){

			@Override
			public String encode(Double key){
				return formatValue(key);
			}
		};

		List<Integer> leftDaughterIndices = getIndices(leftDaughter);
		List<Integer> rightDaughterIndices = getIndices(rightDaughter);
		List<Integer> bestvarIndices = getIndices(bestvar);

		int rows = nrnodes.getIntValue(0);
		int columns = (int)ntree.getRealValue(0);

		List<TreeModel> treeModels = new ArrayList<TreeModel>();

		for(int i = 0; i < columns; i++){
			TreeModel treeModel = encodeTreeModel(
					MiningFunctionType.REGRESSION,
					sublist(leftDaughterIndices, i, rows, columns),
					sublist(rightDaughterIndices, i, rows, columns),
					scoreEncoder,
					sublist(nodepred.getRealValueList(), i, rows, columns),
					sublist(bestvarIndices, i, rows, columns),
					sublist(xbestsplit.getRealValueList(), i, rows, columns)
				);

			treeModels.add(treeModel);
		}

		return encodePMML(MiningFunctionType.REGRESSION, treeModels);
	}

	private PMML convertClassification(Rexp.REXP forest, Rexp.REXP y){
		Rexp.REXP bestvar = field(forest, "bestvar");
		Rexp.REXP treemap = field(forest, "treemap");
		Rexp.REXP nodepred = field(forest, "nodepred");
		Rexp.REXP xbestsplit = field(forest, "xbestsplit");
		Rexp.REXP ncat = field(forest, "ncat");
		Rexp.REXP nrnodes = field(forest, "nrnodes");
		Rexp.REXP ntree = field(forest, "ntree");
		Rexp.REXP xlevels = field(forest, "xlevels");

		initPredictedFields(y);
		initActiveFields(xlevels, ncat);

		ScoreEncoder<Integer> scoreEncoder = new ScoreEncoder<Integer>(){

			@Override
			public String encode(Integer key){
				Value value = getLevel(key.intValue() - 1);

				return value.getValue();
			}
		};

		List<Integer> treemapIndices = getIndices(treemap);
		List<Integer> nodepredIndices = getIndices(nodepred);
		List<Integer> bestvarIndices = getIndices(bestvar);

		int rows = nrnodes.getIntValue(0);
		int columns = (int)ntree.getRealValue(0);

		List<TreeModel> treeModels = new ArrayList<TreeModel>();

		for(int i = 0; i < columns; i++){
			List<Integer> daughters = sublist(treemapIndices, i, 2 * rows, columns);

			TreeModel treeModel = encodeTreeModel(
					MiningFunctionType.CLASSIFICATION,
					sublist(daughters, 0, rows, columns),
					sublist(daughters, 1, rows, columns),
					scoreEncoder,
					sublist(nodepredIndices, i, rows, columns),
					sublist(bestvarIndices, i, rows, columns),
					sublist(xbestsplit.getRealValueList(), i, rows, columns)
				);

			treeModels.add(treeModel);
		}

		return encodePMML(MiningFunctionType.CLASSIFICATION, treeModels);
	}

	private PMML encodePMML(MiningFunctionType miningFunction, List<TreeModel> treeModels){
		DataDictionary dataDictionary = encodeDataDictionary();

		PMML pmml = new PMML(new Header(), dataDictionary, "4.2");

		MiningSchema miningSchema = encodeMiningSchema();

		MiningModel miningModel = new MiningModel(miningSchema, miningFunction);
		pmml = pmml.withModels(miningModel);

		MultipleModelMethodType multipleModelMethod;

		switch(miningFunction){
			case REGRESSION:
				multipleModelMethod = MultipleModelMethodType.AVERAGE;
				break;
			case CLASSIFICATION:
				multipleModelMethod = MultipleModelMethodType.MAJORITY_VOTE;
				break;
			default:
				throw new IllegalArgumentException();
		}

		Segmentation segmentation = new Segmentation(multipleModelMethod);
		miningModel = miningModel.withSegmentation(segmentation);

		for(int i = 0; i < treeModels.size(); i++){
			TreeModel treeModel = treeModels.get(i);
			treeModel = updateMiningSchema(treeModel);

			Segment segment = new Segment()
				.withId(String.valueOf(i + 1))
				.withPredicate(new True())
				.withModel(treeModel);

			segmentation = segmentation.withSegments(segment);
		}

		return pmml;
	}

	private TreeModel updateMiningSchema(TreeModel treeModel){
		Node root = treeModel.getNode();

		FieldCollector fieldCollector = new FieldCollector();
		root.accept(fieldCollector);

		List<MiningField> miningFields = new ArrayList<MiningField>();

		Set<FieldName> fields = fieldCollector.getFields();
		for(FieldName field : fields){
			MiningField miningField = new MiningField(field);

			miningFields.add(miningField);
		}

		MiningSchema miningSchema = treeModel.getMiningSchema();
		miningSchema = addMiningFields(miningSchema, miningFields);

		return treeModel.withMiningSchema(miningSchema);
	}

	private void initFormulaFields(Rexp.REXP terms){
		Rexp.REXP dataClasses = attribute(terms, "dataClasses");

		Rexp.REXP names = attribute(dataClasses, "names");

		for(int i = 0; i < names.getStringValueCount(); i++){
			STRING name = names.getStringValue(i);

			DataField dataField = new DataField()
				.withName(FieldName.create(name.getStrval()));

			STRING dataClass = dataClasses.getStringValue(i);

			String type = dataClass.getStrval();

			if("factor".equals(type)){
				dataField = dataField.withDataType(DataType.STRING)
					.withOptype(OpType.CATEGORICAL);
			} else

			if("logical".equals(type)){
				dataField = dataField.withDataType(DataType.BOOLEAN)
					.withOptype(OpType.CATEGORICAL);
			} else

			if("numeric".equals(type)){
				dataField = dataField.withDataType(DataType.DOUBLE)
					.withOptype(OpType.CONTINUOUS);
			} else

			{
				throw new IllegalArgumentException();
			}

			this.dataFields.add(dataField);
		}
	}

	private void initNonFormulaFields(Rexp.REXP xlevels, Rexp.REXP ncat, Rexp.REXP y){

		// Dependent variable
		{
			DataField dataField = new DataField()
				.withName(FieldName.create("_target"));

			boolean classification = (y != null);

			if(classification){
				dataField = dataField.withDataType(DataType.STRING)
					.withOptype(OpType.CATEGORICAL);
			} else

			{
				dataField = dataField.withDataType(DataType.DOUBLE)
					.withOptype(OpType.CONTINUOUS);
			}

			this.dataFields.add(dataField);
		}

		Rexp.REXP names = attribute(xlevels, "names");

		// Independent variable(s)
		for(int i = 0; i < names.getStringValueCount(); i++){
			STRING name = names.getStringValue(i);

			DataField dataField = new DataField()
				.withName(FieldName.create(name.getStrval()));

			boolean categorical = (ncat.getIntValue(i) > 1);
			if(categorical){
				dataField = dataField.withDataType(DataType.STRING)
					.withOptype(OpType.CATEGORICAL);
			} else

			{
				dataField = dataField.withDataType(DataType.DOUBLE)
					.withOptype(OpType.CONTINUOUS);
			}

			this.dataFields.add(dataField);
		}
	}

	private void initActiveFields(Rexp.REXP xlevels, Rexp.REXP ncat){

		for(int i = 0; i < ncat.getIntValueCount(); i++){
			DataField dataField = this.dataFields.get(i + 1);

			boolean categorical = (ncat.getIntValue(i) > 1);
			if(!categorical){
				continue;
			}

			List<Value> values = dataField.getValues();

			Rexp.REXP xvalues = xlevels.getRexpValue(i);

			for(int j = 0; j < xvalues.getStringValueCount(); j++){
				STRING xvalue = xvalues.getStringValue(j);

				values.add(new Value(xvalue.getStrval()));
			}
		}
	}

	private void initPredictedFields(Rexp.REXP y){
		DataField dataField = this.dataFields.get(0);

		List<Value> values = dataField.getValues();

		Rexp.REXP levels = attribute(y, "levels");

		for(int i = 0; i < levels.getStringValueCount(); i++){
			STRING level = levels.getStringValue(i);

			values.add(new Value(level.getStrval()));
		}
	}

	private DataDictionary encodeDataDictionary(){
		DataDictionary dataDictionary = new DataDictionary()
			.withDataFields(this.dataFields);

		return dataDictionary;
	}

	private MiningSchema encodeMiningSchema(){
		MiningSchema miningSchema = new MiningSchema();

		List<MiningField> miningFields = new ArrayList<MiningField>();

		for(int i = 0; i < this.dataFields.size(); i++){
			DataField dataField = this.dataFields.get(i);

			MiningField miningField = new MiningField(dataField.getName())
				.withUsageType(i == 0 ? FieldUsageType.TARGET : FieldUsageType.ACTIVE);

			miningFields.add(miningField);
		}

		miningSchema = addMiningFields(miningSchema, miningFields);

		return miningSchema;
	}

	private <P extends Number> TreeModel encodeTreeModel(MiningFunctionType miningFunction, List<Integer> leftDaughter, List<Integer> rightDaughter, ScoreEncoder<P> scoreEncoder, List<P> nodepred, List<Integer> bestvar, List<Double> xbestsplit){
		Node root = new Node()
			.withId("1")
			.withPredicate(new True());

		encodeNode(root, 0, leftDaughter, rightDaughter, bestvar, xbestsplit, scoreEncoder, nodepred);

		TreeModel treeModel = new TreeModel(new MiningSchema(), root, miningFunction)
			.withSplitCharacteristic(TreeModel.SplitCharacteristic.BINARY_SPLIT);

		return treeModel;
	}

	private <P extends Number> void encodeNode(Node node, int i, List<Integer> leftDaughter, List<Integer> rightDaughter, List<Integer> bestvar, List<Double> xbestsplit, ScoreEncoder<P> scoreEncoder, List<P> nodepred){
		Predicate leftPredicate = null;
		Predicate rightPredicate = null;

		Integer var = bestvar.get(i);
		if(var != 0){
			DataField dataField = this.dataFields.get(var);

			Double split = xbestsplit.get(i);

			PredicateKey key = new PredicateKey(dataField, split);

			DataType dataType = dataField.getDataType();
			switch(dataType){
				case STRING:
					leftPredicate = this.leftSimpleSetPredicates.getUnchecked(key);
					rightPredicate = this.rightSimpleSetPredicates.getUnchecked(key);
					break;
				case DOUBLE:
				case BOOLEAN:
					leftPredicate = this.leftSimplePredicates.getUnchecked(key);
					rightPredicate = this.rightSimplePredicates.getUnchecked(key);
					break;
				default:
					throw new IllegalArgumentException();
			}
		} else

		{
			P prediction = nodepred.get(i);

			node = node.withScore(scoreEncoder.encode(prediction));
		}

		Integer left = leftDaughter.get(i);
		if(left != 0){
			Node leftChild = new Node()
				.withId(String.valueOf(left))
				.withPredicate(leftPredicate);

			encodeNode(leftChild, left - 1, leftDaughter, rightDaughter, bestvar, xbestsplit, scoreEncoder, nodepred);

			node = node.withNodes(leftChild);
		}

		Integer right = rightDaughter.get(i);
		if(right != 0){
			Node rightChild = new Node()
				.withId(String.valueOf(right))
				.withPredicate(rightPredicate);

			encodeNode(rightChild, right - 1, leftDaughter, rightDaughter, bestvar, xbestsplit, scoreEncoder, nodepred);

			node = node.withNodes(rightChild);
		}
	}

	private SimpleSetPredicate encodeSimpleSetPredicate(DataField dataField, Integer split, boolean leftDaughter){
		SimpleSetPredicate simpleSetPredicate = new SimpleSetPredicate()
			.withField(dataField.getName())
			.withBooleanOperator(SimpleSetPredicate.BooleanOperator.IS_IN)
			.withArray(encodeArray(dataField, split, leftDaughter));

		return simpleSetPredicate;
	}

	private Array encodeArray(DataField dataField, Integer split, boolean leftDaughter){
		String value = formatArrayValue(dataField.getValues(), split, leftDaughter);

		Array array = new Array(value, Array.Type.STRING);

		return array;
	}

	private SimplePredicate encodeSimplePredicate(DataField dataField, Double split, boolean leftDaughter){
		SimplePredicate simplePredicate;

		DataType dataType = dataField.getDataType();

		if((DataType.BOOLEAN).equals(dataType)){
			simplePredicate = new SimplePredicate()
				.withField(dataField.getName())
				.withOperator(SimplePredicate.Operator.EQUAL)
				.withValue(split.doubleValue() <= 0.5d ? Boolean.toString(!leftDaughter) : Boolean.toString(leftDaughter));
		} else

		if((DataType.DOUBLE).equals(dataType)){
			simplePredicate = new SimplePredicate()
				.withField(dataField.getName())
				.withOperator(leftDaughter ? SimplePredicate.Operator.LESS_OR_EQUAL : SimplePredicate.Operator.GREATER_THAN)
				.withValue(formatValue(split));
		} else

		{
			throw new IllegalArgumentException();
		}

		return simplePredicate;
	}

	private MiningSchema addMiningFields(MiningSchema miningSchema, List<MiningField> miningFields){
		Comparator<MiningField> comparator = new Comparator<MiningField>(){

			@Override
			public int compare(MiningField left, MiningField right){
				boolean leftActive = (left.getUsageType()).equals(FieldUsageType.ACTIVE);
				boolean rightActive = (right.getUsageType()).equals(FieldUsageType.ACTIVE);

				if(leftActive && !rightActive){
					return 1;
				} // End if

				if(!leftActive && rightActive){
					return -1;
				}

				return ((left.getName()).getValue()).compareTo((right.getName()).getValue());
			}
		};
		Collections.sort(miningFields, comparator);

		return miningSchema.withMiningFields(miningFields);
	}

	private Value getLevel(int i){
		DataField dataField = this.dataFields.get(0);

		List<Value> values = dataField.getValues();

		return values.get(i);
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

	static
	private String formatValue(Number number){
		double value = number.doubleValue();

		if(DoubleMath.isMathematicalInteger(value)){
			return Long.toString(number.longValue());
		}

		return Double.toString(value);
	}

	static
	private String formatArrayValue(List<Value> values, Integer split, boolean leftDaughter){
		StringBuilder sb = new StringBuilder();

		String sep = "";

		String string = performBinaryExpansion(split);

		for(int i = 0; i < values.size(); i++){
			Value value = values.get(i);

			boolean append;

			// Send "true" categories to the left
			if(leftDaughter){
				append = ((i < string.length()) && (string.charAt(i) == '1'));
			} else

			// Send all other categories to the right
			{
				append = ((i >= string.length()) || (string.charAt(i) == '0'));
			} // End if

			if(append){
				sb.append(sep);

				String element = value.getValue();
				if(element.indexOf(' ') > -1){
					sb.append('\"').append(element).append('\"');
				} else

				{
					sb.append(element);
				}

				sep = " ";
			}
		}

		return sb.toString();
	}

	static
	private String performBinaryExpansion(int value){

		if(value <= 0){
			throw new IllegalArgumentException();
		}

		StringBuilder sb = new StringBuilder();
		sb.append(Integer.toBinaryString(value));

		// Start counting from the rightmost bit
		sb = sb.reverse();

		return sb.toString();
	}

	static
	private Rexp.REXP field(Rexp.REXP rexp, String name){
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
	private Rexp.REXP attribute(Rexp.REXP rexp, String name){
		List<String> attributes = new ArrayList<String>();

		for(int i = 0; i < rexp.getAttrNameCount(); i++){

			if((rexp.getAttrName(i)).equals(name)){
				return rexp.getAttrValue(i);
			}

			attributes.add(rexp.getAttrName(i));
		}

		throw new IllegalArgumentException("Attribute " + name + " not in " + attributes);
	}

	static
	private List<Integer> getIndices(Rexp.REXP rexp){
		List<Integer> intValues = rexp.getIntValueList();
		if(intValues.size() > 0){
			return intValues;
		}

		List<Double> realValues = rexp.getRealValueList();
		if(realValues.size() > 0){
			Function<Number, Integer> function = new Function<Number, Integer>(){

				@Override
				public Integer apply(Number number){
					return asInteger(number);
				}
			};

			return Lists.transform(realValues, function);
		}

		throw new IllegalArgumentException();
	}

	static
	private Integer asInteger(Number number){

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
	private Double asDouble(Number number){
		return (Double)number;
	}

	static
	private <E> List<E> sublist(List<E> list, int i, int rows, int columns){
		return list.subList(i * rows, (i * rows) + rows);
	}

	static
	private interface ScoreEncoder<K extends Number> {

		String encode(K key);
	}

	static
	private class PredicateKey {

		private DataField dataField = null;

		private Number split = null;


		private PredicateKey(DataField dataField, Number split){
			setDataField(dataField);
			setSplit(split);
		}

		@Override
		public int hashCode(){
			return getDataField().hashCode() ^ getSplit().hashCode();
		}

		@Override
		public boolean equals(Object object){

			if(object instanceof PredicateKey){
				PredicateKey that = (PredicateKey)object;

				return (this.getDataField()).equals(that.getDataField()) && (this.getSplit()).equals(that.getSplit());
			}

			return false;
		}

		public DataField getDataField(){
			return this.dataField;
		}

		private void setDataField(DataField dataField){
			this.dataField = dataField;
		}

		public Number getSplit(){
			return this.split;
		}

		private void setSplit(Number split){
			this.split = split;
		}
	}

	static
	private class FieldCollector extends AbstractVisitor {

		private Set<FieldName> fields = new LinkedHashSet<FieldName>();


		@Override
		public VisitorAction visit(SimpleSetPredicate simpleSetPredicate){
			this.fields.add(simpleSetPredicate.getField());

			return super.visit(simpleSetPredicate);
		}

		@Override
		public VisitorAction visit(SimplePredicate simplePredicate){
			this.fields.add(simplePredicate.getField());

			return super.visit(simplePredicate);
		}

		public Set<FieldName> getFields(){
			return this.fields;
		}
	}
}
