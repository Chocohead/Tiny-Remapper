/*
 * Copyright (C) 2016, 2018 Player, asie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.fabricmc.tinyremapper;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

class AsmClassRemapper extends ClassRemapper {

	private final boolean renameInvalidLocals;

	public AsmClassRemapper(ClassVisitor cv, AsmRemapper remapper, boolean renameInvalidLocals) {
		super(cv, remapper);
		this.renameInvalidLocals = renameInvalidLocals;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		String remappedDescriptor = remapper.mapMethodDesc(descriptor);
		String mappedName = remapper.mapMethodName(className, name, descriptor);
		MethodVisitor methodVisitor = cv.visitMethod(access, mappedName, remappedDescriptor, remapper.mapSignature(signature, false), exceptions == null ? null : remapper.mapTypes(exceptions));
		String[] locals = ((AsmRemapper) remapper).getLocalVariables(remapper.mapType(className), mappedName, remappedDescriptor);
		return new AsmMethodRemapper(methodVisitor, remapper, locals, renameInvalidLocals);
	}

	@Override
	protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
		throw new UnsupportedOperationException("Can't remap locals without knowing the method they're for");
	}

	private static class AsmMethodRemapper extends MethodRemapper {
		private final Map<String, Integer> nameCounts = new HashMap<>();
		private final String[] remappedLocals;
		private final boolean renameInvalidLocals;

		public AsmMethodRemapper(MethodVisitor methodVisitor, Remapper remapper, String[] locals, boolean renameInvalidLocals) {
			super(methodVisitor, remapper);
			remappedLocals = locals;
			this.renameInvalidLocals = renameInvalidLocals;
		}

		@Override
		public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
			if (mv == null) return;

			descriptor = remapper.mapDesc(descriptor);

			String mappedName = remappedLocals != null && remappedLocals.length > index ? remappedLocals[index] : null;
			if (mappedName != null) {
				name = mappedName;
			} else if (renameInvalidLocals && !isValidJavaIdentifier(name)) {
				Type type = Type.getType(descriptor);
				boolean plural = false;

				if (type.getSort() == Type.ARRAY) {
					plural = true;
					type = type.getElementType();
				}

				String varName = type.getClassName();
				int dotIdx = varName.lastIndexOf('.');
				if (dotIdx != -1) varName = varName.substring(dotIdx + 1);

				varName = Character.toLowerCase(varName.charAt(0)) + varName.substring(1);
				if (plural) varName += "s";
				name = varName + "_" + nameCounts.compute(varName, (k, v) -> (v == null) ? 1 : v + 1);
			}

			mv.visitLocalVariable(
					name,
					descriptor,
					remapper.mapSignature(signature, true),
					start,
					end,
					index);
		}

		private static boolean isValidJavaIdentifier(String s) {
			if (s.isEmpty()) return false;

			int cp = s.codePointAt(0);
			if (!Character.isJavaIdentifierStart(cp)) return false;

			for (int i = Character.charCount(cp), max = s.length(); i < max; i += Character.charCount(cp)) {
				cp = s.codePointAt(i);
				if (!Character.isJavaIdentifierPart(cp)) return false;
			}

			return true;
		}

		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
			if (mv == null) return;

			Handle implemented = getLambdaImplementedMethod(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);

			if (implemented != null) {
				name = remapper.mapMethodName(implemented.getOwner(), implemented.getName(), implemented.getDesc());
			} else {
				name = remapper.mapInvokeDynamicMethodName(name, descriptor);
			}

			for (int i = 0; i < bootstrapMethodArguments.length; i++) {
				bootstrapMethodArguments[i] = remapper.mapValue(bootstrapMethodArguments[i]);
			}

			mv.visitInvokeDynamicInsn(
					name,
					remapper.mapMethodDesc(descriptor), (Handle) remapper.mapValue(bootstrapMethodHandle),
					bootstrapMethodArguments);
		}

		private static Handle getLambdaImplementedMethod(String name, String desc, Handle bsm, Object... bsmArgs) {
			if (isJavaLambdaMetafactory(bsm)) {
				assert desc.endsWith(";");
				return new Handle(Opcodes.H_INVOKEINTERFACE, desc.substring(desc.lastIndexOf(')') + 2, desc.length() - 1), name, ((Type) bsmArgs[0]).getDescriptor(), true);
			} else {
				System.out.printf("unknown invokedynamic bsm: %s/%s%s (tag=%d iif=%b)%n", bsm.getOwner(), bsm.getName(), bsm.getDesc(), bsm.getTag(), bsm.isInterface());

				return null;
			}
		}

		private static boolean isJavaLambdaMetafactory(Handle bsm) {
			return bsm.getTag() == Opcodes.H_INVOKESTATIC
					&& bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
					&& (bsm.getName().equals("metafactory")
							&& bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
							|| bsm.getName().equals("altMetafactory")
							&& bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"))
					&& !bsm.isInterface();
		}
	}
}
