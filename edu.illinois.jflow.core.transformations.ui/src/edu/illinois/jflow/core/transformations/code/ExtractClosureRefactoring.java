/**
 * This class derives
 * from {@link org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring} and is
 * licensed under the Eclipse Public License.
 */
package edu.illinois.jflow.core.transformations.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.ImportRewriteContext;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.LinkedNodeFinder;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.ui.viewsupport.BasicElementLabels;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;

/**
 * Extracts a closure in a compilation unit based on a text selection range.
 * 
 * @author Nicholas Chen
 */
@SuppressWarnings("restriction")
public class ExtractClosureRefactoring extends Refactoring {
	private ICompilationUnit fCUnit;

	private CompilationUnit fRoot;

	private ImportRewrite fImportRewriter;

	private int fSelectionStart;

	private int fSelectionLength;

	private AST fAST;

	private ASTRewrite fRewriter;

	private ExtractClosureAnalyzer fAnalyzer;

	private List<ParameterInfo> fParameterInfos;

	private static final String EMPTY= ""; //$NON-NLS-1$

	// This section is specific to the API for GPars Dataflow

	private static final String CLOSURE_PARAMETER_NAME= "arguments"; //$NON-NLS-1$

	private static final String CLOSURE_PARAMETER_TYPE= "Object"; //$NON-NLS-1$

	private static final String CLOSURE_METHOD= "doRun"; //$NON-NLS-1$

	private static final String CLOSURE_INVOCATION_METHOD_NAME= "call"; //$NON-NLS-1$

	private static final String CLOSURE_TYPE= "groovyx.gpars.DataflowMessagingRunnable"; //$NON-NLS-1$

	private static final String DATAFLOWQUEUE_TYPE= "groovyx.gpars.dataflow.DataflowQueue"; //$NON-NLS-1$

	private static final String DATAFLOWQUEUE_PUT_METHOD= "bind"; //$NON-NLS-1$

	private static final String GENERIC_CHANNEL_NAME= "channel"; //$NON-NLS-1$

	/**
	 * Creates a new extract closure refactoring
	 * 
	 * @param unit the compilation unit, or <code>null</code> if invoked by scripting
	 * @param selectionStart selection start
	 * @param selectionLength selection end
	 */
	public ExtractClosureRefactoring(ICompilationUnit unit, int selectionStart, int selectionLength) {
		fCUnit= unit;
		fRoot= null;
		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
	}

	/**
	 * Creates a new extract closure refactoring
	 * 
	 * @param astRoot the AST root of an AST created from a compilation unit
	 * @param selectionStart start
	 * @param selectionLength length
	 */
	public ExtractClosureRefactoring(CompilationUnit astRoot, int selectionStart, int selectionLength) {
		this((ICompilationUnit)astRoot.getTypeRoot(), selectionStart, selectionLength);
		fRoot= astRoot;
	}

	@Override
	public String getName() {
		return JFlowRefactoringCoreMessages.ExtractClosureRefactoring_name;
	}

	/**
	 * Checks if the refactoring can be activated. Activation typically means, if a corresponding
	 * menu entry can be added to the UI.
	 * 
	 * @param pm a progress monitor to report progress during activation checking.
	 * @return the refactoring status describing the result of the activation check.
	 * @throws CoreException if checking fails
	 */
	@Override
	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException {
		RefactoringStatus result= new RefactoringStatus();
		pm.beginTask("", 100); //$NON-NLS-1$

		if (fSelectionStart < 0 || fSelectionLength == 0)
			return mergeTextSelectionStatus(result);

		IFile[] changedFiles= ResourceUtil.getFiles(new ICompilationUnit[] { fCUnit });
		result.merge(Checks.validateModifiesFiles(changedFiles, getValidationContext()));
		if (result.hasFatalError())
			return result;
		result.merge(ResourceChangeChecker.checkFilesToBeChanged(changedFiles, new SubProgressMonitor(pm, 1)));

		if (fRoot == null) {
			fRoot= RefactoringASTParser.parseWithASTProvider(fCUnit, true, new SubProgressMonitor(pm, 99));
		}
		fImportRewriter= StubUtility.createImportRewrite(fRoot, true);

		fAST= fRoot.getAST();
		fRoot.accept(createVisitor());

		fSelectionStart= fAnalyzer.getSelection().getOffset();
		fSelectionLength= fAnalyzer.getSelection().getLength();

		result.merge(fAnalyzer.checkInitialConditions(fImportRewriter));
		if (result.hasFatalError())
			return result;
		initializeParameterInfos();
		return result;
	}

	private ASTVisitor createVisitor() throws CoreException {
		fAnalyzer= new ExtractClosureAnalyzer(fCUnit, Selection.createFromStartLength(fSelectionStart, fSelectionLength));
		return fAnalyzer;
	}

	/**
	 * Returns the parameter infos.
	 * 
	 * @return a list of parameter infos.
	 */
	public List<ParameterInfo> getParameterInfos() {
		return fParameterInfos;
	}

	public ICompilationUnit getCompilationUnit() {
		return fCUnit;
	}

	/**
	 * Checks if varargs are ordered correctly.
	 * 
	 * @return validation status
	 */
	public RefactoringStatus checkVarargOrder() {
		for (Iterator<ParameterInfo> iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo info= iter.next();
			if (info.isOldVarargs() && iter.hasNext()) {
				return RefactoringStatus.createFatalErrorStatus(Messages.format(
						JFlowRefactoringCoreMessages.ExtractClosureRefactoring_error_vararg_ordering,
						BasicElementLabels.getJavaElementName(info.getOldName())));
			}
		}
		return new RefactoringStatus();
	}

	/* (non-Javadoc)
	 * Method declared in Refactoring
	 */
	@Override
	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException {
		pm.subTask(EMPTY);

		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkVarargOrder());
		pm.worked(1);
		if (pm.isCanceled())
			throw new OperationCanceledException();

		pm.done();
		return result;
	}

	/* (non-Javadoc)
	 * Method declared in IRefactoring
	 */
	@Override
	public Change createChange(IProgressMonitor pm) throws CoreException {
		pm.beginTask("", 2); //$NON-NLS-1$
		try {
			BodyDeclaration declaration= fAnalyzer.getEnclosingBodyDeclaration();
			fRewriter= ASTRewrite.create(declaration.getAST());

			final CompilationUnitChange result= new CompilationUnitChange(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_change_name, fCUnit);
			result.setSaveMode(TextFileChange.KEEP_SAVE_STATE);

			MultiTextEdit root= new MultiTextEdit();
			result.setEdit(root);

			ASTNode[] selectedNodes= fAnalyzer.getSelectedNodes();

			TextEditGroup closureEditGroup= new TextEditGroup("Extract to Closure");
			result.addTextEditGroup(closureEditGroup);

			// A sentinel is just a placeholder to keep track of the position of insertion
			// For this refactoring, we need to insert two things:
			// 1) The DataflowChannels (if necessary)
			// 2) The DataflowMessagingRunnable
			Block sentinel= fAST.newBlock();
			ListRewrite sentinelRewriter= fRewriter.getListRewrite(selectedNodes[0].getParent(), (ChildListPropertyDescriptor)selectedNodes[0].getLocationInParent());
			sentinelRewriter.insertBefore(sentinel, selectedNodes[0], null);

			// Add the dataflowChannels that are required
			addDataflowChannels(closureEditGroup, sentinel, sentinelRewriter);

			// Add the new closure body
			ClassInstanceCreation dataflowClosure= createNewDataflowClosure(selectedNodes, fCUnit.findRecommendedLineSeparator(), closureEditGroup);
			MethodInvocation closureInvocation= createClosureInvocation(dataflowClosure);

			// Update all references to values written in the closure body to read from channels
			updateReadsToUseDataflowQueue(declaration);

			// Handle InterruptedException from using DataflowChannels
			updateExceptions(declaration, closureEditGroup);

			// Replace the placeholder sentinel with the actual code
			sentinelRewriter.replace(sentinel, fAST.newExpressionStatement(closureInvocation), closureEditGroup);

			if (fImportRewriter.hasRecordedChanges()) {
				TextEdit edit= fImportRewriter.rewriteImports(null);
				root.addChild(edit);
				result.addTextEditGroup(new TextEditGroup(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_organize_imports, new TextEdit[] { edit }));
			}
			root.addChild(fRewriter.rewriteAST());
			return result;
		} finally {
			pm.done();
		}

	}

	private void updateExceptions(BodyDeclaration declaration, TextEditGroup closureEditGroup) {
		// If there was indeed a read using getVal on a DataflowChannel, then there is a potential exception
		if (fAnalyzer.getPotentialReadsOutsideOfClosure().length != 0) {
			MethodDeclaration method= (MethodDeclaration)declaration;
			ListRewrite exceptions= fRewriter.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTIONS_PROPERTY);
			Name newName= ASTNodeFactory.newName(fAST, "InterruptedException");
			exceptions.insertLast(newName, closureEditGroup);
		}
	}

	private ITypeBinding[] filterRuntimeExceptions(ITypeBinding[] exceptions) {
		List<ITypeBinding> result= new ArrayList<ITypeBinding>(exceptions.length);
		for (int i= 0; i < exceptions.length; i++) {
			ITypeBinding exception= exceptions[i];
			if (Bindings.isRuntimeException(exception))
				continue;
			result.add(exception);
		}
		return result.toArray(new ITypeBinding[result.size()]);
	}

	private void updateReadsToUseDataflowQueue(BodyDeclaration declaration) {
		// Update all references to use dataflow channels
		Selection selection= Selection.createFromStartLength(fSelectionStart, fSelectionLength);
		int channelNumber= 0;
		for (IVariableBinding potentialWrites : fAnalyzer.getPotentialReadsOutsideOfClosure()) {
			SimpleName[] references= LinkedNodeFinder.findByBinding(declaration, potentialWrites);
			for (int n= 0; n < references.length; n++) {
				if (!selection.covers(references[n])) {
					fRewriter.replace(references[n], createChannelRead(channelNumber), null);
				}
			}
			channelNumber++;
		}
	}

	private ASTNode createChannelRead(int channelNumber) {
		MethodInvocation methodInvocation= fAST.newMethodInvocation();
		methodInvocation.setExpression(fAST.newSimpleName(GENERIC_CHANNEL_NAME + channelNumber));
		methodInvocation.setName(fAST.newSimpleName("getVal"));
		return methodInvocation;
	}

	//---- Code generation -----------------------------------------------------------------------

	private void addDataflowChannels(TextEditGroup closureEditGroup, Block sentinel, ListRewrite sentinelRewriter) {
		int channelNumber= 0;
		if (fAnalyzer.getPotentialReadsOutsideOfClosure().length != 0) {
			for (IVariableBinding potentialWrites : fAnalyzer.getPotentialReadsOutsideOfClosure()) {
				// Use string generation since this is a single statement
				String channel= "final DataflowQueue<" + resolveType(potentialWrites) + "> channel" + channelNumber + "= new DataflowQueue<" + resolveType(potentialWrites) + ">();";
				ASTNode newStatement= ASTNodeFactory.newStatement(fAST, channel);
				sentinelRewriter.insertBefore(newStatement, sentinel, closureEditGroup);
				channelNumber++;
			}

			fImportRewriter.addImport(DATAFLOWQUEUE_TYPE);
		}
	}


	// TODO: Is there a utility class that does this mapping?
	final static Map<String, String> primitivesToClass= new HashMap<String, String>();
	static {
		primitivesToClass.put("char", "Character");
		primitivesToClass.put("byte", "Byte");
		primitivesToClass.put("short", "Short");
		primitivesToClass.put("int", "Integer");
		primitivesToClass.put("long", "Long");
		primitivesToClass.put("float", "Float");
		primitivesToClass.put("double", "Double");
		primitivesToClass.put("boolean", "Boolean");
	}

	private String resolveType(IVariableBinding binding) {
		ITypeBinding type= binding.getType();
		if (type.isPrimitive())
			return primitivesToClass.get(type.getName());
		return type.getName();
	}

	private VariableDeclaration getVariableDeclaration(ParameterInfo parameter) {
		return ASTNodes.findVariableDeclaration(parameter.getOldBinding(), fAnalyzer.getEnclosingBodyDeclaration());
	}

	private VariableDeclarationStatement createDeclaration(IVariableBinding binding, Expression intilizer) {
		VariableDeclaration original= ASTNodes.findVariableDeclaration(binding, fAnalyzer.getEnclosingBodyDeclaration());
		VariableDeclarationFragment fragment= fAST.newVariableDeclarationFragment();
		fragment.setName((SimpleName)ASTNode.copySubtree(fAST, original.getName()));
		fragment.setInitializer(intilizer);
		VariableDeclarationStatement result= fAST.newVariableDeclarationStatement(fragment);
		result.modifiers().addAll(ASTNode.copySubtrees(fAST, ASTNodes.getModifiers(original)));
		result.setType(ASTNodeFactory.newType(fAST, original, fImportRewriter, new ContextSensitiveImportRewriteContext(original, fImportRewriter)));
		return result;
	}

	private MethodInvocation createClosureInvocation(ClassInstanceCreation dataflowClosure) {
		MethodInvocation closureInvocation= fAST.newMethodInvocation();
		closureInvocation.setName(fAST.newSimpleName(CLOSURE_INVOCATION_METHOD_NAME));
		closureInvocation.setExpression(dataflowClosure);
		createClosureArguments(closureInvocation);
		return closureInvocation;
	}

	private void createClosureArguments(MethodInvocation closureInvocation) {
		List<Expression> arguments= closureInvocation.arguments();
		for (int i= 0; i < fParameterInfos.size(); i++) {
			ParameterInfo parameter= fParameterInfos.get(i);
			arguments.add(ASTNodeFactory.newName(fAST, parameter.getOldName()));
		}
	}

	/**
	 * Create an ASTNode similar to
	 * 
	 * new DataFlowMessagingRunnable(...){...}
	 * 
	 * @param selectedNodes
	 * @param findRecommendedLineSeparator
	 * @param editGroup
	 * @return
	 */
	private ClassInstanceCreation createNewDataflowClosure(ASTNode[] selectedNodes, String findRecommendedLineSeparator, TextEditGroup editGroup) {
		ClassInstanceCreation dataflowClosure= fAST.newClassInstanceCreation();

		// Create the small chunks
		augmentWithTypeInfo(dataflowClosure);
		augmentWithConstructorArgument(dataflowClosure);
		augmentWithAnonymousClassDeclaration(dataflowClosure, selectedNodes, editGroup);

		return dataflowClosure;
	}

	private void augmentWithTypeInfo(ClassInstanceCreation dataflowClosure) {
		ImportRewriteContext context= new ContextSensitiveImportRewriteContext(fAnalyzer.getEnclosingBodyDeclaration(), fImportRewriter);
		fImportRewriter.addImport(CLOSURE_TYPE, context);
		dataflowClosure.setType(fAST.newSimpleType(fAST.newName("DataflowMessagingRunnable")));
	}

	@SuppressWarnings("unchecked")
	private void augmentWithConstructorArgument(ClassInstanceCreation dataflowClosure) {
		String argumentsCount= new Integer(fParameterInfos.size()).toString();
		dataflowClosure.arguments().add(fAST.newNumberLiteral(argumentsCount));
	}

	private void augmentWithAnonymousClassDeclaration(ClassInstanceCreation dataflowClosure, ASTNode[] selectedNodes, TextEditGroup editGroup) {
		AnonymousClassDeclaration closure= fAST.newAnonymousClassDeclaration();
		closure.bodyDeclarations().add(createRunMethodForClosure(selectedNodes, editGroup));
		dataflowClosure.setAnonymousClassDeclaration(closure);
	}

	/**
	 * Create a ASTNode similar to
	 * 
	 * protected void doRun(Object... arguments) { ... }
	 * 
	 * @param selectedNodes - The statements to be enclosed in the doRun(...) method
	 * @param editGroup
	 * @return
	 */
	private Object createRunMethodForClosure(ASTNode[] selectedNodes, TextEditGroup editGroup) {
		MethodDeclaration runMethod= fAST.newMethodDeclaration();
		runMethod.modifiers().addAll(ASTNodeFactory.newModifiers(fAST, Modifier.PROTECTED));
		runMethod.setReturnType2(fAST.newPrimitiveType(org.eclipse.jdt.core.dom.PrimitiveType.VOID));
		runMethod.setName(fAST.newSimpleName(CLOSURE_METHOD));
		runMethod.parameters().add(createObjectArrayArgument());
		runMethod.setBody(createClosureBody(selectedNodes, editGroup));
		return runMethod;
	}

	/**
	 * Creates an the Object... arguments type
	 * 
	 * @return
	 */
	private Object createObjectArrayArgument() {
		SingleVariableDeclaration parameter= fAST.newSingleVariableDeclaration();
		parameter.setVarargs(true);
		parameter.setType(fAST.newSimpleType(fAST.newSimpleName(CLOSURE_PARAMETER_TYPE)));
		parameter.setName(fAST.newSimpleName(CLOSURE_PARAMETER_NAME));
		return parameter;
	}

	private Block createClosureBody(ASTNode[] selectedNodes, TextEditGroup editGroup) {
		Block methodBlock= fAST.newBlock();
		ListRewrite statements= fRewriter.getListRewrite(methodBlock, Block.STATEMENTS_PROPERTY);

		// Locals that are not passed as an arguments since the extracted method only
		// writes to them
		IVariableBinding[] methodLocals= fAnalyzer.getMethodLocals();
		for (int i= 0; i < methodLocals.length; i++) {
			if (methodLocals[i] != null) {
				methodBlock.statements().add(createDeclaration(methodLocals[i], null));
			}
		}

		// Update the bindings to the parameters
		int argumentPosition= 0;
		for (ParameterInfo parameter : fParameterInfos) {
			for (int n= 0; n < selectedNodes.length; n++) {
				SimpleName[] oldNames= LinkedNodeFinder.findByBinding(selectedNodes[n], parameter.getOldBinding());
				for (int i= 0; i < oldNames.length; i++) {
					fRewriter.replace(oldNames[i], createCastParameters(parameter, argumentPosition), null);
				}
			}
			argumentPosition++;
		}

		ListRewrite source= fRewriter.getListRewrite(
				selectedNodes[0].getParent(),
				(ChildListPropertyDescriptor)selectedNodes[0].getLocationInParent());
		ASTNode toMove= source.createMoveTarget(
				selectedNodes[0], selectedNodes[selectedNodes.length - 1], null, editGroup);
		statements.insertLast(toMove, editGroup);

		// Add the potential writes at the end (in case multiple writes have occurred and we only want the latest values)  
		int channelNumber= 0;
		for (IVariableBinding potentialWrites : fAnalyzer.getPotentialReadsOutsideOfClosure()) {
			// Use string generation since this is a single statement
			String channel= GENERIC_CHANNEL_NAME + channelNumber + "." + DATAFLOWQUEUE_PUT_METHOD + "(" + potentialWrites.getName() + ");";
			ASTNode newStatement= ASTNodeFactory.newStatement(fAST, channel);
			statements.insertLast(newStatement, editGroup);
			channelNumber++;
		}


		return methodBlock;
	}

	private ASTNode createCastParameters(ParameterInfo parameter, int argumentsPosition) {
		ParenthesizedExpression argumentExpression= fAST.newParenthesizedExpression();
		CastExpression castExpression= fAST.newCastExpression();

		VariableDeclaration infoDecl= getVariableDeclaration(parameter);
		castExpression.setType(ASTNodeFactory.newType(fAST, infoDecl, fImportRewriter, null));

		ArrayAccess arrayAccess= fAST.newArrayAccess();
		arrayAccess.setArray(fAST.newSimpleName(CLOSURE_PARAMETER_NAME));
		arrayAccess.setIndex(fAST.newNumberLiteral(Integer.toString(argumentsPosition)));
		castExpression.setExpression(arrayAccess);

		argumentExpression.setExpression(castExpression);
		return argumentExpression;
	}

	//---- Helper methods ------------------------------------------------------------------------

	private void initializeParameterInfos() {
		IVariableBinding[] arguments= fAnalyzer.getArguments();
		fParameterInfos= new ArrayList<ParameterInfo>(arguments.length);
		ASTNode root= fAnalyzer.getEnclosingBodyDeclaration();
		ParameterInfo vararg= null;
		for (int i= 0; i < arguments.length; i++) {
			IVariableBinding argument= arguments[i];
			if (argument == null)
				continue;
			VariableDeclaration declaration= ASTNodes.findVariableDeclaration(argument, root);
			boolean isVarargs= declaration instanceof SingleVariableDeclaration
					? ((SingleVariableDeclaration)declaration).isVarargs()
					: false;
			ParameterInfo info= new ParameterInfo(argument, getType(declaration, isVarargs), argument.getName(), i);
			if (isVarargs) {
				vararg= info;
			} else {
				fParameterInfos.add(info);
			}
		}
		if (vararg != null) {
			fParameterInfos.add(vararg);
		}
	}

	private RefactoringStatus mergeTextSelectionStatus(RefactoringStatus status) {
		status.addFatalError(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_no_set_of_statements);
		return status;
	}

	private String getType(VariableDeclaration declaration, boolean isVarargs) {
		String type= ASTNodes.asString(ASTNodeFactory.newType(declaration.getAST(), declaration, fImportRewriter, new ContextSensitiveImportRewriteContext(declaration, fImportRewriter)));
		if (isVarargs)
			return type + ParameterInfo.ELLIPSIS;
		else
			return type;
	}


}