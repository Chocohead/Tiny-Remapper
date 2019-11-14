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
		final Map<String, String> classMap;

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
			throw new UnsupportedOperationException("Tiny V2 support coming soon");
		} else {
			throw new IOException("Invalid mapping version!");
		}
	}

	private static void readV1(BufferedReader reader, int fromIndex, int toIndex,
			BiConsumer<String, String> classMappingConsumer,
			BiConsumer<Mapping, String> fieldMappingConsumer,
			BiConsumer<Mapping, String> methodMappingConsumer) throws IOException {
		Map<String, String> obfFrom = new HashMap<>();
		List<String[]> linesStageTwo = new ArrayList<>();

		String line;
		while ((line = reader.readLine()) != null) {
			String[] splitLine = line.split("\t");

			if (splitLine.length >= 2) {
				if ("CLASS".equals(splitLine[0])) {
					classMappingConsumer.accept(splitLine[1 + fromIndex], splitLine[1 + toIndex]);
					obfFrom.put(splitLine[1], splitLine[1 + fromIndex]);
				} else {
					linesStageTwo.add(splitLine);
				}
			}
		}

		SimpleClassMapper descObfFrom = new SimpleClassMapper(obfFrom);

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

			String owner = obfFrom.getOrDefault(splitLine[1], splitLine[1]);
			String name = splitLine[3 + fromIndex];
			String desc = descFixer.apply(splitLine[2]);

			Mapping mapping = new Mapping(owner, name, desc);
			String nameTo = splitLine[3 + toIndex];

			consumer.accept(mapping, nameTo);
		}
	}
}
