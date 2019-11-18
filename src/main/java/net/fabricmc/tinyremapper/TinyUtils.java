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

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;

import org.objectweb.asm.commons.Remapper;

public final class TinyUtils {
	public static class Mapping {
		public final String owner, name, desc;

		public Mapping(String owner, String name, String desc) {
			this.owner = owner;
			this.name = name;
			this.desc = desc;
		}

		@Override
		public boolean equals(Object other) {
			if (other == null || !(other instanceof Mapping)) {
				return false;
			} else {
				Mapping otherM = (Mapping) other;
				return owner.equals(otherM.owner) && name.equals(otherM.name) && Objects.equals(desc, otherM.desc);
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(owner, name, desc);
		}
	}

	private static class SimpleClassMapper extends Remapper {
		private final Map<String, String> classMap;

		public SimpleClassMapper(Map<String, String> map) {
			this.classMap = map;
		}

		@Override
		public String map(String typeName) {
			return classMap.getOrDefault(typeName, typeName);
		}
	}

	@FunctionalInterface
	private interface MappingProvider extends IMappingProvider {
		@Override
		default void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
			load(classMap, fieldMap, methodMap, new Map<String, String[]>() {
				@Override
				public int size() {
					return 0;
				}

				@Override
				public boolean isEmpty() {
					return true;
				}

				@Override
				public boolean containsKey(Object key) {
					return false;
				}

				@Override
				public boolean containsValue(Object value) {
					return false;
				}

				@Override
				public String[] get(Object key) {
					return null;
				}

				@Override
				public String[] put(String key, String[] value) {
					return null;
				}

				@Override
				public void putAll(Map<? extends String, ? extends String[]> map) {
				}

				@Override
				public String[] remove(Object key) {
					return null;
				}

				@Override
				public void clear() {
				}

				@Override
				public Set<String> keySet() {
					return Collections.emptySet();
				}

				@Override
				public Collection<String[]> values() {
					return Collections.emptySet();
				}

				@Override
				public Set<Entry<String, String[]>> entrySet() {
					return Collections.emptySet();
				}
			});
		}

		@Override
		void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap);
	}

	private TinyUtils() {

	}

	public static IMappingProvider createTinyMappingProvider(final Path mappings, String fromM, String toM) {
		return createInternalMappingProvider(mappings, fromM, toM);
	}

	private static MappingProvider createInternalMappingProvider(final Path mappings, String fromM, String toM) {
		return (classMap, fieldMap, methodMap, localMap) -> {
			try (BufferedReader reader = getMappingReader(mappings)) {
				readInternal(reader, fromM, toM, classMap, fieldMap, methodMap, localMap);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			System.out.printf("%s: %d classes, %d methods, %d fields%n", mappings.getFileName().toString(), classMap.size(), methodMap.size(), fieldMap.size());
		};
	}

	private static BufferedReader getMappingReader(Path file) throws IOException {
		InputStream is = Files.newInputStream(file);

		if (file.getFileName().toString().endsWith(".gz")) {
			is = new GZIPInputStream(is);
		}

		return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
	}

	public static IMappingProvider createTinyMappingProvider(final BufferedReader reader, String fromM, String toM) {
		return createInternalMappingProvider(reader, fromM, toM);
	}

	private static MappingProvider createInternalMappingProvider(final BufferedReader reader, String fromM, String toM) {
		return (classMap, fieldMap, methodMap, localMap) -> {
			try {
				readInternal(reader, fromM, toM, classMap, fieldMap, methodMap, localMap);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			System.out.printf("%d classes, %d methods, %d fields%n", classMap.size(), methodMap.size(), fieldMap.size());
		};
	}

	private static void readInternal(BufferedReader reader, String fromM, String toM, Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) throws IOException {
		TinyUtils.read(reader, fromM, toM, (classFrom, classTo) -> {
			classMap.put(classFrom, classTo);
		}, (fieldFrom, nameTo) -> {
			fieldMap.put(fieldFrom.owner + "/" + MemberInstance.getFieldId(fieldFrom.name, fieldFrom.desc), nameTo);
		}, (methodFrom, nameTo) -> {
			methodMap.put(methodFrom.owner + "/" + MemberInstance.getMethodId(methodFrom.name, methodFrom.desc), nameTo);
		}, (methodFrom, paramNames) -> {
			localMap.put(methodFrom.owner + '/' + MemberInstance.getMethodId(methodFrom.name, methodFrom.desc), paramNames);
		});
	}

	@Deprecated
	public static void read(BufferedReader reader, String from, String to,
			BiConsumer<String, String> classMappingConsumer,
			BiConsumer<Mapping, String> fieldMappingConsumer,
			BiConsumer<Mapping, String> methodMappingConsumer)
					throws IOException {
		read(reader, from, to, classMappingConsumer, fieldMappingConsumer, methodMappingConsumer, (mapping, params) -> {});
	}

	public static void read(BufferedReader reader, String from, String to,
			BiConsumer<String, String> classMappingConsumer,
			BiConsumer<Mapping, String> fieldMappingConsumer,
			BiConsumer<Mapping, String> methodMappingConsumer,
			BiConsumer<Mapping, String[]> localMappingConsumer) throws IOException {
		String headerLine = reader.readLine();

		if (headerLine == null) {
			throw new EOFException();
		} else if (headerLine.startsWith("v1\t")) {
			String[] header = headerLine.split("\t");
			if (header.length <= 1 || !header[0].equals("v1")) {
				throw new IOException("Invalid mapping version!");
			}

			List<String> headerList = Arrays.asList(header);
			int fromIndex = headerList.indexOf(from) - 1;
			int toIndex = headerList.indexOf(to) - 1;

			if (fromIndex < 0) throw new IOException("Could not find mapping '" + from + "'!");
			if (toIndex < 0) throw new IOException("Could not find mapping '" + to + "'!");

			readV1(reader, fromIndex, toIndex, classMappingConsumer, fieldMappingConsumer, methodMappingConsumer);
		} else if (headerLine.startsWith("tiny\t2\t")) {
			readV2(reader, from, to, headerLine, classMappingConsumer, fieldMappingConsumer, methodMappingConsumer, localMappingConsumer);
		} else {
			throw new IOException("Invalid mapping version!");
		}
	}

	private static void readV1(BufferedReader reader, int fromIndex, int toIndex,
			BiConsumer<String, String> classMappingConsumer,
			BiConsumer<Mapping, String> fieldMappingConsumer,
			BiConsumer<Mapping, String> methodMappingConsumer) throws IOException {
		Map<String, String> obfFrom = fromIndex != 0 ? new HashMap<>() : null;
		List<String[]> linesStageTwo = new ArrayList<>();

		String line;
		while ((line = reader.readLine()) != null) {
			String[] splitLine = line.split("\t");

			if (splitLine.length >= 2) {
				if ("CLASS".equals(splitLine[0])) {
					String mappedName = splitLine[1 + toIndex];
					if (!mappedName.isEmpty()) {
						classMappingConsumer.accept(splitLine[1 + fromIndex], mappedName);
						if (obfFrom != null) obfFrom.put(splitLine[1], splitLine[1 + fromIndex]);
					}
				} else {
					linesStageTwo.add(splitLine);
				}
			}
		}

		SimpleClassMapper descObfFrom = new SimpleClassMapper(obfFrom != null ? obfFrom : Collections.emptyMap());

		for (String[] splitLine : linesStageTwo) {
			String type = splitLine[0];

			BiConsumer<Mapping, String> consumer;
			UnaryOperator<String> descFixer;
			if ("FIELD".equals(type)) {
				consumer = fieldMappingConsumer;
				descFixer = descObfFrom::mapDesc;
			} else if ("METHOD".equals(type)) {
				consumer = methodMappingConsumer;
				descFixer = descObfFrom::mapMethodDesc;
			} else {
				continue;
			}

			String owner = descObfFrom.map(splitLine[1]);
			String name = splitLine[3 + fromIndex];
			String desc = descFixer.apply(splitLine[2]);

			Mapping mapping = new Mapping(owner, name, desc);
			String nameTo = splitLine[3 + toIndex];

			if (!nameTo.isEmpty()) consumer.accept(mapping, nameTo);
		}
	}

	private static void readV2(BufferedReader reader, String from, String to, String headerLine,
			BiConsumer<String, String> classMappingConsumer,
			BiConsumer<Mapping, String> fieldMappingConsumer,
			BiConsumer<Mapping, String> methodMappingConsumer,
			BiConsumer<Mapping, String[]> localMappingConsumer) throws IOException {
		String[] parts;

		if (!headerLine.startsWith("tiny\t2\t") || (parts = splitAtTab(headerLine, 0, 5)).length < 5) { //min. tiny + major version + minor version + 2 name spaces
			throw new IOException("Invalid/unsupported tiny file (incorrect header)");
		}

		List<String> namespaces = Arrays.asList(parts).subList(3, parts.length);
		int nsA = namespaces.indexOf(from);
		int nsB = namespaces.indexOf(to);
		Map<String, String> obfFrom = nsA != 0 ? new HashMap<>() : null;

		Map<String, String> classes = new HashMap<>();
		Map<Mapping, String> methods = new HashMap<>();
		Map<Mapping, String> fields = new HashMap<>();
		Map<Mapping, String[]> locals = new HashMap<>();

		int partCountHint = 2 + namespaces.size(); // suitable for members, which should be the majority
		boolean escapedNames = false;

		boolean inHeader = true;
		boolean inClass = false;
		boolean inMethod = false;

		String className = null;
		Mapping member = null;

		int lineNumber = 1;
		for (String line = reader.readLine(); line != null; line = reader.readLine(), lineNumber++) {
			if (line.isEmpty()) continue;

			int indent = 0;
			while (indent < line.length() && line.charAt(indent) == '\t') {
				indent++;
			}

			parts = splitAtTab(line, indent, partCountHint);
			String section = parts[0];

			if (indent == 0) {
				inHeader = inClass = inMethod = false;

				if ("c".equals(section)) { // class: c <names>...
					if (parts.length != namespaces.size() + 1) throw new IOException("Invalid class declaration on line " + lineNumber);

					className = unescapeOpt(parts[1 + nsA], escapedNames);
					String mappedName = unescapeOpt(parts[1 + nsB], escapedNames);

					if (!mappedName.isEmpty()) {
						classMappingConsumer.accept(className, mappedName);
						classes.put(className, mappedName);
						if (obfFrom != null) obfFrom.put(unescapeOpt(parts[1], escapedNames), mappedName);
					}

					inClass = true;
				}
			} else if (indent == 1) {
				inMethod = false;

				if (inHeader) { // header k/v
					if ("escaped-names".equals(section)) {
						escapedNames = true;
					}
				} else if (inClass && ("m".equals(section) || "f".equals(section))) { // method/field: m/f <descA> <names>...
					boolean isMethod = "m".equals(section);
					if (parts.length != namespaces.size() + 2) throw new IOException("Invalid " + (isMethod ? "metho" : "fiel") + "d declaration on line " + lineNumber);

					String memberDesc = unescapeOpt(parts[1], escapedNames);
					String memberName = unescapeOpt(parts[2 + nsA], escapedNames);
					String mappedName = unescapeOpt(parts[2 + nsB], escapedNames);
					member = new Mapping(className, memberName, memberDesc);
					inMethod = isMethod;

					if (!mappedName.isEmpty()) (isMethod ? methods : fields).put(member, mappedName);
				}
			} else if (indent == 2) {
				if (inMethod && "p".equals(section)) { // method parameter: p <lv-index> <names>...
					if (parts.length != namespaces.size() + 2) throw new IOException("Invalid method parameter declaration on line " + lineNumber);

					String mappedName = unescapeOpt(parts[2 + nsB], escapedNames);
					if (!mappedName.isEmpty()) {
						int varLvIndex = Integer.parseInt(parts[1]);

						String[] methodLocals = locals.get(member);
						if (methodLocals == null || methodLocals.length <= varLvIndex) {
							String[] longerLocals = new String[varLvIndex + 1];
							if (methodLocals != null) System.arraycopy(methodLocals, 0, longerLocals, 0, methodLocals.length);
							locals.put(member, methodLocals = longerLocals);
						}

						assert methodLocals[varLvIndex] == null;
						methodLocals[varLvIndex] = mappedName;
					}
				} else if (inMethod && "v".equals(section)) { // method variable: v <lv-index> <lv-start-offset> <optional-lvt-index> <names>...
					if (parts.length != namespaces.size() + 4) throw new IOException("Invalid method variable declaration on line " + lineNumber);

					String mappedName = unescapeOpt(parts[4 + nsB], escapedNames);
					if (!mappedName.isEmpty()) {
						int varLvIndex = Integer.parseInt(parts[1]);
						int varStartOpIdx = Integer.parseInt(parts[2]);
						int varLvtIndex = Integer.parseInt(parts[3]);

						//Don't currently support this as it stands, neither does Yarn so it could be worse
						throw new UnsupportedOperationException(String.format("%1$s local %2$d: %5$s, start @ %3$d, index %4$d", member, varLvIndex, varStartOpIdx, varLvtIndex, mappedName));
					}
				}
			}
		}

		UnaryOperator<Mapping> descFixer, methodDescFixer;
		if (obfFrom != null) {
			Remapper remapper = new SimpleClassMapper(obfFrom);
			descFixer = mapping -> new Mapping(mapping.owner, mapping.name, remapper.mapDesc(mapping.desc));
			methodDescFixer = mapping -> new Mapping(mapping.owner, mapping.name, remapper.mapMethodDesc(mapping.desc));
		} else {
			methodDescFixer = descFixer = UnaryOperator.identity();
		}

		for (Entry<Mapping, String> entry : methods.entrySet()) {
			methodMappingConsumer.accept(methodDescFixer.apply(entry.getKey()), entry.getValue());
		}
		for (Entry<Mapping, String> entry : fields.entrySet()) {
			fieldMappingConsumer.accept(descFixer.apply(entry.getKey()), entry.getValue());
		}

		for (Entry<Mapping, String[]> entry : locals.entrySet()) {
			Mapping mapping = entry.getKey();
			localMappingConsumer.accept(new Mapping(classes.getOrDefault(mapping.owner, mapping.owner), mapping.name, mapping.desc), entry.getValue());
		}
	}

	private static String[] splitAtTab(String s, int offset, int partCountHint) {
		String[] ret = new String[Math.max(1, partCountHint)];
		int partCount = 0;
		int pos;

		while ((pos = s.indexOf('\t', offset)) >= 0) {
			if (partCount == ret.length) ret = Arrays.copyOf(ret, ret.length * 2);
			ret[partCount++] = s.substring(offset, pos);
			offset = pos + 1;
		}

		if (partCount == ret.length) ret = Arrays.copyOf(ret, ret.length + 1);
		ret[partCount++] = s.substring(offset);

		return partCount == ret.length ? ret : Arrays.copyOf(ret, partCount);
	}

	private static String unescapeOpt(String str, boolean escapedNames) {
		return escapedNames ? unescape(str) : str;
	}

	private static String unescape(String str) {
		int pos = str.indexOf('\\');
		if (pos < 0) return str;

		StringBuilder ret = new StringBuilder(str.length() - 1);
		int start = 0;

		do {
			ret.append(str, start, pos);
			pos++;
			int type;

			if (pos >= str.length()) {
				throw new RuntimeException("incomplete escape sequence at the end");
			} else if ((type = escaped.indexOf(str.charAt(pos))) < 0) {
				throw new RuntimeException("invalid escape character: \\"+str.charAt(pos));
			} else {
				ret.append(toEscape.charAt(type));
			}

			start = pos + 1;
		} while ((pos = str.indexOf('\\', start)) >= 0);

		ret.append(str, start, str.length());

		return ret.toString();
	}

	private static final String toEscape = "\\\n\r\0\t";
	private static final String escaped = "\\nr0t";
}
