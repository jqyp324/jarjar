/**
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tonicsystems.jarjar.transform.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class Wildcard {

    @Nonnull
    public static Wildcard createWildcard(@Nonnull AbstractPattern pattern) {
        String result = (pattern instanceof ClassRename) ? ((ClassRename) pattern).getResult() : "";
        String expr = pattern.getPattern();
        if (expr.indexOf('/') >= 0)
            throw new IllegalArgumentException("Patterns cannot contain slashes");
        return new Wildcard(expr.replace('.', '/'), result);
    }

    @Nonnull
    public static List<Wildcard> createWildcards(@Nonnull Iterable<? extends AbstractPattern> patterns) {
        List<Wildcard> wildcards = new ArrayList<Wildcard>();
        for (AbstractPattern pattern : patterns) {
            wildcards.add(createWildcard(pattern));
        }
        return wildcards;
    }

    private static final Pattern dstar = Pattern.compile("\\*\\*");
    private static final Pattern star = Pattern.compile("\\*");
    private static final Pattern estar = Pattern.compile("\\+\\??\\)\\Z");

    private static enum State {

        NORMAL, ESCAPE;
    }

    private final Pattern pattern;
    private final int count;
    private final ArrayList<Object> parts = new ArrayList<Object>(16); // kept for debugging

    public Wildcard(@Nonnull String pattern, @Nonnull String result) {
        if (pattern.equals("**"))
            throw new IllegalArgumentException("'**' is not a valid pattern");
        if (!isPossibleQualifiedName(pattern, "/*"))
            throw new IllegalArgumentException("Not a valid package pattern: " + pattern);
        if (pattern.indexOf("***") >= 0)
            throw new IllegalArgumentException("The sequence '***' is invalid in a package pattern");

        String regex = pattern;
        regex = replaceAllLiteral(regex, dstar, "(.+?)");   // One wildcard test requires the argument to be allowably empty.
        regex = replaceAllLiteral(regex, star, "([^/]+)");
        regex = replaceAllLiteral(regex, estar, "*\\??)");  // Although we replaced with + above, we mean *
        this.pattern = Pattern.compile("\\A" + regex + "\\Z");
        this.count = this.pattern.matcher("foo").groupCount();

        // TODO: check for illegal characters
        int max = 0;
        State state = State.NORMAL;
        for (int i = 0, mark = 0, len = result.length(); i < len + 1; i++) {
            char ch = (i == len) ? '@' : result.charAt(i);
            switch (state) {
                case NORMAL:
                    if (ch == '@') {
                        parts.add(result.substring(mark, i).replace('.', '/'));
                        mark = i + 1;
                        state = State.ESCAPE;
                    }
                    break;
                case ESCAPE:
                    switch (ch) {
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                        case '8':
                        case '9':
                            break;
                        default:
                            if (i == mark)
                                throw new IllegalArgumentException("Backslash not followed by a digit");
                            int n = Integer.parseInt(result.substring(mark, i));
                            if (n > max)
                                max = n;
                            parts.add(Integer.valueOf(n));
                            mark = i--;
                            state = State.NORMAL;
                            break;
                    }
                    break;
            }
        }

        if (count < max)
            throw new IllegalArgumentException("Result includes impossible placeholder \"@" + max + "\": " + result);
        // System.err.println(this);
    }

    public boolean matches(@Nonnull String value) {
        return getMatcher(value) != null;
    }

    @CheckForNull
    public String replace(@Nonnull String value) {
        Matcher matcher = getMatcher(value);
        if (matcher != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                Object part = parts.get(i);
                if (part instanceof String)
                    sb.append((String) part);
                else
                    sb.append(matcher.group((Integer) part));
            }
            return sb.toString();
        }
        return null;
    }

    @CheckForNull
    private Matcher getMatcher(@Nonnull String value) {
        if (!isPossibleQualifiedName(value, "/"))
            return null;
        Matcher matcher = pattern.matcher(value);
        if (matcher.matches())
            return matcher;
        return null;
    }

    public static final String PACKAGE_INFO = "package-info";

    private static boolean isPossibleQualifiedName(@Nonnull String value, @Nonnull String extraAllowedCharacters) {
        // package-info violates the spec for Java Identifiers.
        // Nevertheless, expressions that end with this string are still legal.
        // See 7.4.1.1 of the Java language spec for discussion.
        if (value.endsWith(PACKAGE_INFO)) {
            value = value.substring(0, value.length() - PACKAGE_INFO.length());
        }
        for (int i = 0, len = value.length(); i < len; i++) {
            char c = value.charAt(i);
            if (Character.isJavaIdentifierPart(c))
                continue;
            if (extraAllowedCharacters.indexOf(c) >= 0)
                continue;
            return false;
        }
        return true;
    }

    @Nonnull
    private static String replaceAllLiteral(@Nonnull String value, @Nonnull Pattern pattern, @Nonnull String replace) {
        return pattern.matcher(value).replaceAll(Matcher.quoteReplacement(replace));
    }

    @Override
    public String toString() {
        return "Wildcard{pattern=" + pattern + ",parts=" + parts + "}";
    }
}
