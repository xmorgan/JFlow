package edu.illinois.jflow.wala.core.ui.applications.test;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.ibm.wala.ide.tests.util.EclipseTestUtil.ZippedProjectData;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.util.CancelException;

import edu.illinois.jflow.jflow.wala.dataflowanalysis.PDGPartitionerChecker;
import edu.illinois.jflow.jflow.wala.dataflowanalysis.ProgramDependenceGraph;
import edu.illinois.jflow.wala.core.ui.tests.Activator;
import edu.illinois.jflow.wala.core.ui.tests.JFlowTest;

public class LireAnalysisTest extends JFlowTest {

	private static final String PROJECT_NAME= "Lire";

	private static final String PROJECT_ZIP= "lire-workspace.zip";

	public static final ZippedProjectData PROJECT= new ZippedProjectData(Activator.getDefault(), PROJECT_NAME, PROJECT_ZIP);

	public LireAnalysisTest() {
		super(PROJECT);
	}

	@Override
	protected String getTestPackageName() {
		return "edu/illinois/jflow/benchmark";
	}

	@Test
	public void testLireIndexingExample() throws IllegalArgumentException, IOException, CancelException, InvalidClassFileException {
		IR ir= retrieveMethodIR(constructFullyQualifiedClass(), "serialIndexImages", "", "V");
		ProgramDependenceGraph pdg= ProgramDependenceGraph.make(ir, engine.buildClassHierarchy());
		List<List<Integer>> selections= selectionFromArray(new int[][] { { 20 }, { 23 }, { 27 }, { 31, 32 } });
		PDGPartitionerChecker checker= PDGPartitionerChecker.makePartitionChecker(pdg, selections);
	}
}
