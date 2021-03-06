/*
 * Copyright (C) 2010, Red Hat Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.eclipse.jgit.ignore;

import static org.eclipse.jgit.junit.Assert.assertEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

/**
 * Tests ignore node behavior on the local filesystem.
 */
public class IgnoreNodeTest extends RepositoryTestCase {
	private static final FileMode D = FileMode.TREE;

	private static final FileMode F = FileMode.REGULAR_FILE;

	private static final boolean ignored = true;

	private static final boolean tracked = false;

	private TreeWalk walk;

	@Test
	public void testRules() throws IOException {
		writeIgnoreFile(".git/info/exclude", "*~", "/out");

		writeIgnoreFile(".gitignore", "*.o", "/config");
		writeTrashFile("config/secret", "");
		writeTrashFile("mylib.c", "");
		writeTrashFile("mylib.c~", "");
		writeTrashFile("mylib.o", "");

		writeTrashFile("out/object/foo.exe", "");
		writeIgnoreFile("src/config/.gitignore", "lex.out");
		writeTrashFile("src/config/lex.out", "");
		writeTrashFile("src/config/config.c", "");
		writeTrashFile("src/config/config.c~", "");
		writeTrashFile("src/config/old/lex.out", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, ignored, "config");
		assertEntry(F, ignored, "config/secret");
		assertEntry(F, tracked, "mylib.c");
		assertEntry(F, ignored, "mylib.c~");
		assertEntry(F, ignored, "mylib.o");

		assertEntry(D, ignored, "out");
		assertEntry(D, ignored, "out/object");
		assertEntry(F, ignored, "out/object/foo.exe");

		assertEntry(D, tracked, "src");
		assertEntry(D, tracked, "src/config");
		assertEntry(F, tracked, "src/config/.gitignore");
		assertEntry(F, tracked, "src/config/config.c");
		assertEntry(F, ignored, "src/config/config.c~");
		assertEntry(F, ignored, "src/config/lex.out");
		assertEntry(D, tracked, "src/config/old");
		assertEntry(F, ignored, "src/config/old/lex.out");
		endWalk();
	}

	@Test
	public void testNegation() throws IOException {
		// ignore all *.o files and ignore all "d" directories
		writeIgnoreFile(".gitignore", "*.o", "d");

		// negate "ignore" for a/b/keep.o file only
		writeIgnoreFile("src/a/b/.gitignore", "!keep.o");
		writeTrashFile("src/a/b/keep.o", "");
		writeTrashFile("src/a/b/nothere.o", "");

		// negate "ignore" for "d"
		writeIgnoreFile("src/c/.gitignore", "!d");
		// negate "ignore" for c/d/keep.o file only
		writeIgnoreFile("src/c/d/.gitignore", "!keep.o");
		writeTrashFile("src/c/d/keep.o", "");
		writeTrashFile("src/c/d/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "src");
		assertEntry(D, tracked, "src/a");
		assertEntry(D, tracked, "src/a/b");
		assertEntry(F, tracked, "src/a/b/.gitignore");
		assertEntry(F, tracked, "src/a/b/keep.o");
		assertEntry(F, ignored, "src/a/b/nothere.o");

		assertEntry(D, tracked, "src/c");
		assertEntry(F, tracked, "src/c/.gitignore");
		assertEntry(D, tracked, "src/c/d");
		assertEntry(F, tracked, "src/c/d/.gitignore");
		assertEntry(F, tracked, "src/c/d/keep.o");
		// must be ignored: "!d" should not negate *both* "d" and *.o rules!
		assertEntry(F, ignored, "src/c/d/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=407475
	 */
	@Test
	public void testNegateAllExceptJavaInSrc() throws IOException {
		// ignore all files except from src directory
		writeIgnoreFile(".gitignore", "/*", "!/src/");
		writeTrashFile("nothere.o", "");

		// ignore all files except java
		writeIgnoreFile("src/.gitignore", "*", "!*.java");

		writeTrashFile("src/keep.java", "");
		writeTrashFile("src/nothere.o", "");
		writeTrashFile("src/a/nothere.o", "");

		beginWalk();
		assertEntry(F, ignored, ".gitignore");
		assertEntry(F, ignored, "nothere.o");
		assertEntry(D, tracked, "src");
		assertEntry(F, ignored, "src/.gitignore");
		assertEntry(D, ignored, "src/a");
		assertEntry(F, ignored, "src/a/nothere.o");
		assertEntry(F, tracked, "src/keep.java");
		assertEntry(F, ignored, "src/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=407475
	 */
	@Test
	public void testNegationAllExceptJavaInSrcAndExceptChildDirInSrc()
			throws IOException {
		// ignore all files except from src directory
		writeIgnoreFile(".gitignore", "/*", "!/src/");
		writeTrashFile("nothere.o", "");

		// ignore all files except java in src folder and all children folders.
		// Last ignore rule breaks old jgit via bug 407475
		writeIgnoreFile("src/.gitignore", "*", "!*.java", "!*/");

		writeTrashFile("src/keep.java", "");
		writeTrashFile("src/nothere.o", "");
		writeTrashFile("src/a/keep.java", "");
		writeTrashFile("src/a/keep.o", "");

		beginWalk();
		assertEntry(F, ignored, ".gitignore");
		assertEntry(F, ignored, "nothere.o");
		assertEntry(D, tracked, "src");
		assertEntry(F, ignored, "src/.gitignore");
		assertEntry(D, tracked, "src/a");
		assertEntry(F, tracked, "src/a/keep.java");
		assertEntry(F, tracked, "src/a/keep.o");
		assertEntry(F, tracked, "src/keep.java");
		assertEntry(F, ignored, "src/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=448094
	 */
	@Test
	public void testRepeatedNegation() throws IOException {
		writeIgnoreFile(".gitignore", "e", "!e", "e", "!e", "e");

		writeTrashFile("e/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, ignored, "e");
		assertEntry(F, ignored, "e/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=448094
	 */
	@Test
	public void testRepeatedNegationInDifferentFiles1() throws IOException {
		writeIgnoreFile(".gitignore", "*.o", "e");

		writeIgnoreFile("e/.gitignore", "!e");
		writeTrashFile("e/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, ignored, "e");
		assertEntry(F, ignored, "e/.gitignore");
		assertEntry(F, ignored, "e/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=448094
	 */
	@Test
	public void testRepeatedNegationInDifferentFiles2() throws IOException {
		writeIgnoreFile(".gitignore", "*.o", "e");

		writeIgnoreFile("a/.gitignore", "!e");
		writeTrashFile("a/e/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/.gitignore");
		assertEntry(D, tracked, "a/e");
		assertEntry(F, ignored, "a/e/nothere.o");
		endWalk();
	}

	/*
	 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=448094
	 */
	@Test
	public void testRepeatedNegationInDifferentFiles3() throws IOException {
		writeIgnoreFile(".gitignore", "*.o");

		writeIgnoreFile("a/.gitignore", "e");
		writeIgnoreFile("a/b/.gitignore", "!e");
		writeTrashFile("a/b/e/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/.gitignore");
		assertEntry(D, tracked, "a/b");
		assertEntry(F, tracked, "a/b/.gitignore");
		assertEntry(D, tracked, "a/b/e");
		assertEntry(F, ignored, "a/b/e/nothere.o");
		endWalk();
	}

	@Test
	public void testRepeatedNegationInDifferentFiles4() throws IOException {
		writeIgnoreFile(".gitignore", "*.o");

		writeIgnoreFile("a/.gitignore", "e");
		// Rules are never empty: WorkingTreeIterator optimizes empty rules away
		// paranoia check in case this optimization will be removed
		writeIgnoreFile("a/b/.gitignore", "#");
		writeIgnoreFile("a/b/c/.gitignore", "!e");
		writeTrashFile("a/b/c/e/nothere.o", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/.gitignore");
		assertEntry(D, tracked, "a/b");
		assertEntry(F, tracked, "a/b/.gitignore");
		assertEntry(D, tracked, "a/b/c");
		assertEntry(F, tracked, "a/b/c/.gitignore");
		assertEntry(D, tracked, "a/b/c/e");
		assertEntry(F, ignored, "a/b/c/e/nothere.o");
		endWalk();
	}

	@Test
	public void testEmptyIgnoreNode() {
		// Rules are never empty: WorkingTreeIterator optimizes empty files away
		// So we have to test it manually in case third party clients use
		// IgnoreNode directly.
		IgnoreNode node = new IgnoreNode();
		assertEquals(MatchResult.CHECK_PARENT, node.isIgnored("", false));
		assertEquals(MatchResult.CHECK_PARENT, node.isIgnored("", false, false));
		assertEquals(MatchResult.CHECK_PARENT_NEGATE_FIRST_MATCH,
				node.isIgnored("", false, true));
	}

	@Test
	public void testEmptyIgnoreRules() throws IOException {
		IgnoreNode node = new IgnoreNode();
		node.parse(writeToString("", "#", "!", "[[=a=]]"));
		assertEquals(new ArrayList<>(), node.getRules());
		node.parse(writeToString(" ", " / "));
		assertEquals(2, node.getRules().size());
	}

	@Test
	public void testSlashOnlyMatchesDirectory() throws IOException {
		writeIgnoreFile(".gitignore", "out/");
		writeTrashFile("out", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(F, tracked, "out");

		FileUtils.delete(new File(trash, "out"));
		writeTrashFile("out/foo", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, ignored, "out");
		assertEntry(F, ignored, "out/foo");
		endWalk();
	}

	@Test
	public void testSlashMatchesDirectory() throws IOException {
		writeIgnoreFile(".gitignore", "out2/");

		writeTrashFile("out1/out1", "");
		writeTrashFile("out1/out2", "");
		writeTrashFile("out2/out1", "");
		writeTrashFile("out2/out2", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "out1");
		assertEntry(F, tracked, "out1/out1");
		assertEntry(F, tracked, "out1/out2");
		assertEntry(D, ignored, "out2");
		assertEntry(F, ignored, "out2/out1");
		assertEntry(F, ignored, "out2/out2");
		endWalk();
	}

	@Test
	public void testWildcardWithSlashMatchesDirectory() throws IOException {
		writeIgnoreFile(".gitignore", "out2*/");

		writeTrashFile("out1/out1.txt", "");
		writeTrashFile("out1/out2", "");
		writeTrashFile("out1/out2.txt", "");
		writeTrashFile("out1/out2x/a", "");
		writeTrashFile("out2/out1.txt", "");
		writeTrashFile("out2/out2.txt", "");
		writeTrashFile("out2x/out1.txt", "");
		writeTrashFile("out2x/out2.txt", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "out1");
		assertEntry(F, tracked, "out1/out1.txt");
		assertEntry(F, tracked, "out1/out2");
		assertEntry(F, tracked, "out1/out2.txt");
		assertEntry(D, ignored, "out1/out2x");
		assertEntry(F, ignored, "out1/out2x/a");
		assertEntry(D, ignored, "out2");
		assertEntry(F, ignored, "out2/out1.txt");
		assertEntry(F, ignored, "out2/out2.txt");
		assertEntry(D, ignored, "out2x");
		assertEntry(F, ignored, "out2x/out1.txt");
		assertEntry(F, ignored, "out2x/out2.txt");
		endWalk();
	}

	@Test
	public void testWithSlashDoesNotMatchInSubDirectory() throws IOException {
		writeIgnoreFile(".gitignore", "a/b");
		writeTrashFile("a/a", "");
		writeTrashFile("a/b", "");
		writeTrashFile("src/a/a", "");
		writeTrashFile("src/a/b", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/a");
		assertEntry(F, ignored, "a/b");
		assertEntry(D, tracked, "src");
		assertEntry(D, tracked, "src/a");
		assertEntry(F, tracked, "src/a/a");
		assertEntry(F, tracked, "src/a/b");
		endWalk();
	}

	@Test
	public void testNoPatterns() throws IOException {
		writeIgnoreFile(".gitignore", "", " ", "# comment", "/");
		writeTrashFile("a/a", "");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/a");
		endWalk();
	}

	@Test
	public void testLeadingSpaces() throws IOException {
		writeTrashFile("  a/  a", "");
		writeTrashFile("  a/ a", "");
		writeTrashFile("  a/a", "");
		writeTrashFile(" a/  a", "");
		writeTrashFile(" a/ a", "");
		writeTrashFile(" a/a", "");
		writeIgnoreFile(".gitignore", " a", "  a");
		writeTrashFile("a/  a", "");
		writeTrashFile("a/ a", "");
		writeTrashFile("a/a", "");

		beginWalk();
		assertEntry(D, ignored, "  a");
		assertEntry(F, ignored, "  a/  a");
		assertEntry(F, ignored, "  a/ a");
		assertEntry(F, ignored, "  a/a");
		assertEntry(D, ignored, " a");
		assertEntry(F, ignored, " a/  a");
		assertEntry(F, ignored, " a/ a");
		assertEntry(F, ignored, " a/a");
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, tracked, "a");
		assertEntry(F, ignored, "a/  a");
		assertEntry(F, ignored, "a/ a");
		assertEntry(F, tracked, "a/a");
		endWalk();
	}

	@Test
	public void testTrailingSpaces() throws IOException {
		// Windows can't create files with trailing spaces
		// If this assumption fails the test is halted and ignored.
		org.junit.Assume.assumeFalse(SystemReader.getInstance().isWindows());
		writeTrashFile("a  /a", "");
		writeTrashFile("a  /a ", "");
		writeTrashFile("a  /a  ", "");
		writeTrashFile("a /a", "");
		writeTrashFile("a /a ", "");
		writeTrashFile("a /a  ", "");
		writeTrashFile("a/a", "");
		writeTrashFile("a/a ", "");
		writeTrashFile("a/a  ", "");

		writeIgnoreFile(".gitignore", "a\\ ", "a \\ ");

		beginWalk();
		assertEntry(F, tracked, ".gitignore");
		assertEntry(D, ignored, "a  ");
		assertEntry(F, ignored, "a  /a");
		assertEntry(F, ignored, "a  /a ");
		assertEntry(F, ignored, "a  /a  ");
		assertEntry(D, ignored, "a ");
		assertEntry(F, ignored, "a /a");
		assertEntry(F, ignored, "a /a ");
		assertEntry(F, ignored, "a /a  ");
		assertEntry(D, tracked, "a");
		assertEntry(F, tracked, "a/a");
		assertEntry(F, ignored, "a/a ");
		assertEntry(F, ignored, "a/a  ");
		endWalk();
	}

	@Test
	public void testToString() throws Exception {
		assertEquals(Arrays.asList("").toString(), new IgnoreNode().toString());
		assertEquals(Arrays.asList("hello").toString(),
				new IgnoreNode(Arrays.asList(new FastIgnoreRule("hello")))
						.toString());
	}

	private void beginWalk() throws CorruptObjectException {
		walk = new TreeWalk(db);
		walk.addTree(new FileTreeIterator(db));
	}

	private void endWalk() throws IOException {
		assertFalse("Not all files tested", walk.next());
	}

	private void assertEntry(FileMode type, boolean entryIgnored,
			String pathName) throws IOException {
		assertTrue("walk has entry", walk.next());
		assertEquals(pathName, walk.getPathString());
		assertEquals(type, walk.getFileMode(0));

		WorkingTreeIterator itr = walk.getTree(0, WorkingTreeIterator.class);
		assertNotNull("has tree", itr);
		assertEquals("is ignored", entryIgnored, itr.isEntryIgnored());
		if (D.equals(type))
			walk.enterSubtree();
	}

	private void writeIgnoreFile(String name, String... rules)
			throws IOException {
		StringBuilder data = new StringBuilder();
		for (String line : rules)
			data.append(line + "\n");
		writeTrashFile(name, data.toString());
	}

	private InputStream writeToString(String... rules) throws IOException {
		StringBuilder data = new StringBuilder();
		for (String line : rules) {
			data.append(line + "\n");
		}
		return new ByteArrayInputStream(data.toString().getBytes("UTF-8"));
	}
}
