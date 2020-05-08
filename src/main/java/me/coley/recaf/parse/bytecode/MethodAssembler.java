package me.coley.recaf.parse.bytecode;

import me.coley.analysis.value.VirtualValue;
import me.coley.recaf.Recaf;
import me.coley.recaf.config.ConfAssembler;
import me.coley.analysis.value.AbstractValue;
import me.coley.recaf.parse.bytecode.ast.*;
import me.coley.recaf.parse.bytecode.exception.ASTParseException;
import me.coley.recaf.parse.bytecode.exception.AssemblerException;
import me.coley.recaf.parse.bytecode.exception.VerifierException;
import me.coley.recaf.util.AccessFlag;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.Map;

/**
 * Bytecode assembler for methods.
 *
 * @author Matt
 */
public class MethodAssembler {
	private final String declaringType;
	private final ConfAssembler config;
	private Map<AbstractInsnNode, AST> insnToAST;
	private Frame<AbstractValue>[] frames;

	/**
	 * @param declaringType
	 * 		Internal name of class declaring the method to be assembled.
	 * @param config
	 * 		Assembler config.
	 */
	public MethodAssembler(String declaringType, ConfAssembler config) {
		this.declaringType = declaringType;
		this.config = config;
	}

	/**
	 * @param result
	 * 		AST parse result.
	 *
	 * @return Generated {@link MethodNode}.
	 *
	 * @throws AssemblerException
	 * 		<ul>
	 * 		<li>When the given AST contains errors</li>
	 * 		<li>When the given AST is missing a definition</li>
	 * 		</ul>
	 */
	public MethodNode compile(ParseResult<RootAST> result) throws AssemblerException {
		if(!result.isSuccess()) {
			ASTParseException cause = result.getProblems().get(0);
			AssemblerException ex  = new AssemblerException(cause, "AST must not contain errors", cause.getLine());
			ex.addSubExceptions(result.getProblems());
			throw ex;
		}
		RootAST root = result.getRoot();
		// Get definition
		MethodDefinitionAST definition = root.search(MethodDefinitionAST.class).stream().findFirst().orElse(null);
		if (definition == null)
			throw new AssemblerException("AST must have definition statement");
		int access = definition.getModifierMask();
		String name = definition.getName().getName();
		String desc = definition.getDescriptor();
		String[] exceptions = toExceptions(root);
		SignatureAST signatureAST = root.search(SignatureAST.class).stream().findFirst().orElse(null);
		String signature = (signatureAST == null) ? null : signatureAST.getSignature();
		MethodNode node = new MethodNode(access, name, desc, signature, exceptions);
		// Check if method is abstract, do no further handling
		if(AccessFlag.isAbstract(access))
			return node;
		// Create label mappings
		Map<String, LabelNode> labels = new HashMap<>();
		root.search(LabelAST.class).forEach(lbl -> labels.put(lbl.getName().getName(), new LabelNode()));
		// Parse try-catches
		for(TryCatchAST tc : root.search(TryCatchAST.class)) {
			LabelNode start = labels.get(tc.getLblStart().getName());
			if(start == null)
				throw new AssemblerException("No label associated with try-catch start: " +
						tc.getLblStart().getName(), tc.getLine());
			LabelNode end = labels.get(tc.getLblEnd().getName());
			if(end == null)
				throw new AssemblerException("No label associated with try-catch end: " +
						tc.getLblEnd().getName(), tc.getLine());
			LabelNode handler = labels.get(tc.getLblHandler().getName());
			if(handler == null)
				throw new AssemblerException("No label associated with try-catch handler: " +
						tc.getLblHandler().getName(), tc.getLine());
			String type = tc.getType().getType();
			node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, type));
		}
		// Parse variables (name to index)
		Variables variables = new Variables(AccessFlag.isStatic(access), declaringType);
		variables.visit(root);
		// Parse instructions
		insnToAST = new HashMap<>();
		node.instructions = new InsnList();
		for(AST ast : root.getChildren()) {
			AbstractInsnNode insn;
			if(ast instanceof LabelAST)
				insn = labels.get(((LabelAST) ast).getName().getName());
			else if(ast instanceof AliasAST)
				continue;
			else if(ast instanceof Instruction)
				insn = ((Instruction) ast).compile(labels, variables);
			else
				continue;
			node.instructions.add(insn);
			insnToAST.put(insn, ast);
		}
		// Set stack size (dummy) and max local count.
		node.maxStack = 0xFF;
		node.maxLocals = variables.getMax();
		// Verify code is valid & store analyzed stack data.
		// Use the saved data to fill in missing variable types.
		if (config.verify) {
			frames = verify(node);
			variables.visitWithFrames(frames, labels);
		}
		if (config.variables) {
			node.localVariables = variables.getVariables(labels);
		}
		return node;
	}

	/**
	 * Verify the generated method.
	 *
	 * @param generated
	 * 		Method generated by this assembler.
	 *
	 * @return Analyzed frames of the method.
	 *
	 * @throws AssemblerException
	 * 		Wrapped verification exception.
	 */
	private Frame<AbstractValue>[] verify(MethodNode generated) throws VerifierException {
		return new Verifier(this, declaringType).verify(generated);
	}

	/**
	 * @return Analyzed frames. Will be {@code null} if analysis failed.
	 */
	public Frame<AbstractValue>[] getFrames() {
		return frames;
	}

	/**
	 * @param insn
	 * 		Generated instruction.
	 *
	 * @return Line instruction was generated from.
	 */
	public int getLine(AbstractInsnNode insn) {
		if (insnToAST == null)
			return -1;
		AST ast = insnToAST.get(insn);
		return ast != null ? ast.getLine() : -1;
	}

	/**
	 * @param root
	 * 		AST of method.
	 *
	 * @return All thrown types.
	 */
	private static String[] toExceptions(RootAST root) {
		return root.search(ThrowsAST.class).stream()
				.map(ast -> ast.getType().getType())
				.toArray(String[]::new);
	}

	static {
		VirtualValue.setParentCheck((parent, child) -> Recaf.getCurrentWorkspace().getHierarchyGraph()
				.getAllParents(child.getInternalName())
					.anyMatch(n -> n != null && n.equals(parent.getInternalName())));
	}
}
