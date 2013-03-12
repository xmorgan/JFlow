package edu.illinois.jflow.jflow.wala.dataflowanalysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.ibm.wala.cast.java.loader.JavaSourceLoaderImpl.ConcreteJavaMethod;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.graph.labeled.SlowSparseNumberedLabeledGraph;

/**
 * This is a dependence graph that is a simplified version of what you will find in a traditional
 * Program Dependence Graph (PDG). The main job of this class is to map dependencies at the
 * statement level instead of individual WALA IR. This mapping allows us to map back to our source
 * from the WALA IR.
 * 
 * @author nchen
 * 
 */
public class ProgramDependenceGraph extends SlowSparseNumberedLabeledGraph<Statement, String> {

	public static boolean DEBUG= true;

	private IR ir;

	// Maps a line number (in the text editor) to a statement
	private Map<Integer, Statement> sourceLineMapping;

	// Maps the particular SSAInstruction to the containing statement
	private Map<SSAInstruction, Statement> instruction2Statement;

	// Maps the particular SSAInstruction to its index in the IR instruction array
	// The index is needed for determining the local names
	private Map<SSAInstruction, Integer> instruction2Index;

	public static ProgramDependenceGraph make(IR ir) throws InvalidClassFileException {
		ProgramDependenceGraph g= new ProgramDependenceGraph(ir);
		g.populate();
		return g;
	}

	public ProgramDependenceGraph(IR ir) {
		super("");
		sourceLineMapping= new HashMap<Integer, Statement>();
		instruction2Statement= new HashMap<SSAInstruction, Statement>();
		instruction2Index= new HashMap<SSAInstruction, Integer>();
		this.ir= ir;
	}

	private void populate() throws InvalidClassFileException {
		// 1. Get all the "normal" instructions - meaning that we exclude SSAPiInstruction, SSAPhiInstruction and SSAGetCaughtExceptionInstructions
		// 2. Collect each instruction into the statement object corresponding to its source line number.
		createStatementsFromInstructions();

		// 3. Make each statement into its own node.
		createGraphNodes();

		// 4. Set up the dependencies between each node.
		addDependencyEdges();

	}

	private void addDependencyEdges() {
		DefUse DU= new DefUse(ir);

		for (SSAInstruction instruction : instruction2Statement.keySet()) {
			Statement defStatement= instruction2Statement.get(instruction);
			for (int def= 0; def < instruction.getNumberOfDefs(); def++) {
				int SSAVariable= instruction.getDef(def);
				for (SSAInstruction use : Iterator2Iterable.make(DU.getUses(SSAVariable))) {
					Statement useStatement= instruction2Statement.get(use);
					Integer instructionIndex= instruction2Index.get(use);
					String variableName= SSAVariableToLocalNameIfPossible(instructionIndex, ir, SSAVariable);
					addEdge(defStatement, useStatement, variableName);
				}
			}
		}
	}

	private String SSAVariableToLocalNameIfPossible(Integer instructionIndex, IR ir, int SSAVariable) {
		StringBuilder sb= new StringBuilder();

		if (instructionIndex == null) {
			sb.append(String.format("v%d", SSAVariable));
		} else {
			String[] localNames= ir.getLocalNames(instructionIndex, SSAVariable);
			if (localNames != null) {
				sb.append(Arrays.toString(localNames));
			} else {
				sb.append(String.format("v%d", SSAVariable));
			}
		}
		return sb.toString();
	}

	private void createStatementsFromInstructions() throws InvalidClassFileException {
		SSAInstruction[] instructions= ir.getInstructions();

		//1. Create statements for normal instructions
		IMethod method= ir.getMethod();
		for (int index= 0; index < instructions.length; index++) {
			int lineNumber= getLineNumber(index, method);
			mapInstruction(lineNumber, instructions[index], index);
		}

		//2. Add PhiInstructions, which are treated differently and not included in the instruction index
		// Instead, they are only included in the basic blocks so we need to iterate over them. When we find a PhiInstruction,
		// we associate it with the first instruction we see. If there is no first instruction, then this is an empty basic block and we can skip it.
		for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
			int firstInstructionIndex= bb.getFirstInstructionIndex();
			int lastInstructionIndex= bb.getLastInstructionIndex();

			if (firstInstructionIndex < 0)
				continue; // No instructions in this basic block, skip it

			SSAInstruction instruction= locateFirstValidInstruction(firstInstructionIndex, lastInstructionIndex);

			Statement statement= instruction2Statement.get(instruction);

			for (SSAPhiInstruction phi : Iterator2Iterable.make(bb.iteratePhis())) {
				statement.add(phi);
				instruction2Statement.put(phi, statement);
			}
		}

	}

	/*
	 * Need to loop through the instruction indices range since some of the instructions could be null
	 */
	private SSAInstruction locateFirstValidInstruction(int firstInstructionIndex, int lastInstructionIndex) {
		SSAInstruction[] instructions= ir.getInstructions();

		for (int index= firstInstructionIndex; index <= lastInstructionIndex; index++) {
			if (instructions[index] != null)
				return instructions[index];
		}
		return null;
	}

	private int getLineNumber(int index, IMethod method) throws InvalidClassFileException {
		int lineNumber= method.getLineNumber(index);
		return lineNumber;
	}

	private void createGraphNodes() {
		for (Statement statement : sourceLineMapping.values()) {
			addNode(statement);
		}
	}

	/**
	 * Manages bookkeeping of instructions.
	 * <ol>
	 * <li>Stores the instruction into the "right" statement. (Line number -&gt; statement -&gt;
	 * instruction(s))</li>
	 * <li>Creates a mapping from instruction to statement for fast access (instruction -&gt;
	 * statement)</li>
	 * <li>Creates a mapping from instruction to their index in the IR instruction array.</li>
	 * </ol>
	 * 
	 * @param lineNumber The corresponding line number in the source file
	 * @param instruction The instruction we are handling
	 * @param index The index of the instruction in the IR instruction arra
	 */
	private void mapInstruction(int lineNumber, SSAInstruction instruction, int index) {
		if (DEBUG) {
			System.out.println("LINE: " + lineNumber + ": " + instruction);
		}
		if (instruction != null) {
			Statement statement= sourceLineMapping.get(lineNumber);
			if (emptyStatementForLine(statement)) {
				statement= new Statement(lineNumber);
				sourceLineMapping.put(lineNumber, statement);
			}
			statement.add(instruction);
			instruction2Statement.put(instruction, statement);
			instruction2Index.put(instruction, index);
		}
	}

	private boolean emptyStatementForLine(Statement statement) {
		return statement == null;
	}

	/**
	 * This method is only valid after the call to populate().
	 * 
	 * @param instruction SSAInstruction in our IR
	 * @return the corresponding statement that contains this instruction
	 */
	private int instructionToLineNumber(SSAInstruction instruction) {
		Statement statement= instruction2Statement.get(instruction);
		return statement.getLineNumber();
	}
}