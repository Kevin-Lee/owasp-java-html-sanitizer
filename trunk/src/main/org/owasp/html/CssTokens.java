// Copyright (c) 2013, Mike Samuel
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
// Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
// Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
// Neither the name of the OWASP nor the names of its contributors may
// be used to endorse or promote products derived from this software
// without specific prior written permission.
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
// FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
// COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
// BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
// ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package org.owasp.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

/**
 * Given a string of CSS, produces a string of normalized CSS with certain
 * useful properties detailed below.
 * <ul>
 *   <li>All runs of white-space and comment tokens (including CDO and CDC)
 *     have been replaced with a single space character.</li>
 *   <li>All strings are quoted and escapes are escaped according to the
 *     following scheme:
 *     <table>
 *       <tr><td>NUL</td>            <td><code>\0</code></tr>
 *       <tr><td>line feed</td>      <td><code>\a</code></tr>
 *       <tr><td>vertical feed</td>  <td><code>\c</code></tr>
 *       <tr><td>carriage return</td><td><code>\d</code></tr>
 *       <tr><td>double quote</td>   <td><code>\22</code></tr>
 *       <tr><td>ampersand &amp;</td><td><code>\26</code></tr>
 *       <tr><td>single quote</td>   <td><code>\27</code></tr>
 *       <tr><td>left-angle &lt;</td><td><code>\3c</code></tr>
 *       <tr><td>rt-angle &gt;</td>  <td><code>\3e</code></tr>
 *       <tr><td>back slash</td>     <td><code>\\</code></tr>
 *       <tr><td>dash</td>           <td><code>-</code> or <code>\-</code></tr>
 *       <tr><td>all others</td>     <td>raw</td></tr>
 *     </table>
 *     The dash may be encoded as necessary to prevent the valid CSS,
 *     <pre>.foo-\2d&gt;span { color:red }</pre>
 *     which specifies the selector for the {@code <span>} children of elements
 *     with class ".foo--" from being rendered to include an ignorable
 *     {@code -->} token.
 *   </li>
 *   <li>All <code>url(&hellip;)</code> tokens are quoted.
 *   <li>All keywords, identifiers, and hex literals are lower-case and have
 *       embedded escape sequences decoded.</li>
 *   <li>All brackets nest properly.</li>
 *   <li>Does not contain the sequence {@code <!--} or {@code -->}.</li>
 *   <li>All delimiters that can start longer tokens are followed by a space.
 * </ul>
 */
final class CssTokens implements Iterable<String> {

static final boolean DEBUG = false;

  public final String normalizedCss;
  public final Brackets brackets;
  private final int[] tokenBreaks;
  private final TokenType[] tokenTypes;

  public TokenIterator start() {
    return new TokenIterator(tokenTypes.length);
  }

  public TokenIterator iterator() { return start(); }

  public static CssTokens lex(String css) {
    Lexer lexer = new Lexer(css);
    lexer.lex();
    return lexer.build();
  }

  /** A cursor into a list of tokens. */
  public final class TokenIterator implements Iterator<String> {
    private int tokenIndex = 0;
    private final int limit;

    TokenIterator(int limit) {
      this.limit = limit;
    }

    public boolean hasNext() {
      return hasToken();
    }

    public String next() {
      String token = token();
      advance();
      return token;
    }

    public @Nullable TokenIterator spliceToEnd() {
      if (!hasNext()) { throw new NoSuchElementException(); }
      int end = brackets.partner(tokenIndex);
      if (end < 0) {
        return null;
      }
      TokenIterator between = new TokenIterator(end);
      between.tokenIndex = tokenIndex + 1;
      tokenIndex = end + 1;
      return between;
    }

    public int tokenIndex() {
      return tokenIndex;
    }

    public int startOffset() {
      return tokenBreaks[tokenIndex];
    }

    public int endOffset() {
      return tokenBreaks[tokenIndex+1];
    }

    public String token() {
      return normalizedCss.substring(startOffset(), endOffset());
    }

    public boolean hasToken() {
      return tokenIndex < limit;
    }

    public boolean hasTokenAfterSpace() {
      while (hasToken()) {
        if (type() != TokenType.WHITESPACE) { return true; }
        advance();
      }
      return false;
    }

    /** The type of the current token. */
    public TokenType type() {
      return tokenTypes[tokenIndex];
    }

    public void seek(int tokenIndex) {
      this.tokenIndex = tokenIndex;
    }

    public void advance() {
      if (!hasToken()) { throw new NoSuchElementException(); }
      ++tokenIndex;
    }

    public void remove() throws UnsupportedOperationException {
      throw new UnsupportedOperationException();
    }
  }

  private CssTokens(
      String normalizedCss, Brackets brackets, int[] tokenBreaks,
      TokenType[] tokenTypes) {
    this.normalizedCss = normalizedCss;
    this.brackets = brackets;
    this.tokenBreaks = tokenBreaks;
    this.tokenTypes = tokenTypes;
  }

  public enum TokenType {
    /** An identifier. */
    IDENT,
    /** An identifier prefixed with a period. */
    DOT_IDENT,
    /** A function name and opening bracket. */
    FUNCTION,
    /** An {@code @<identifier>} directive token. */
    AT,
    /** A hash token that contains non-hex characters. */
    HASH_ID,
    /** A hash token that could be a color literal. */
    HASH_UNRESTRICTED,
    /** A quoted string. */
    STRING,
    /** A URL of the form <code>url("...")</code>. */
    URL,
    /** A single character. */
    DELIM,
    /** A scalar numeric value. */
    NUMBER,
    /** A percentage. */
    PERCENTAGE,
    /** A numeric value with a unit suffix. */
    DIMENSION,
    /** {@code U+<hex-or-qmark>} */
    UNICODE_RANGE,
    /**
     * include-match, dash-match, prefix-match, suffix-match, substring-match
     */
    MATCH,
    /** {@code ||} */
    COLUMN,
    /** A run of white-space, comment, CDO, and CDC tokens. */
    WHITESPACE,
    /** {@code :} */
    COLON,
    /** {@code ;} */
    SEMICOLON,
    /** {@code ,} */
    COMMA,
    /** {@code [} */
    LEFT_SQUARE,
    /** {@code ]} */
    RIGHT_SQUARE,
    /** {@code (} */
    LEFT_PAREN,
    /** {@code )} */
    RIGHT_PAREN,
    /** <code>{</code> */
    LEFT_CURLY,
    /** <code>}</code> */
    RIGHT_CURLY,
    ;
  }

  /**
   * Maps tokens to their partners.  A close bracket token like {@code (} may
   * have a partner token like {@code )} if properly nested, and vice-versa.
   */
  static final class Brackets {
    /**
     * For each token index, the index of the indexed token's partner or -1 if
     * it has none.
     */
    private final int[] brackets;

    private Brackets(int[] brackets) {
      this.brackets = brackets;
    }

    /** The index of the partner token or -1 if none. */
    int partner(int tokenIndex) {
if (DEBUG) System.err.println("index=" + tokenIndex + ", brackets=" + Arrays.toString(brackets));
      int bracketIndex = bracketIndexForToken(tokenIndex);
if (DEBUG) System.err.println("\tbracketIndex=" + bracketIndex);
      if (bracketIndex < 0) { return -1; }
      return brackets[(bracketIndex << 1) + 1];
    }

    int bracketIndexForToken(int target) {
      // Binary search by leftmost element of pair.
      int left = 0;
      int right = brackets.length >> 1;
      while (left < right) {
        int mid = (left + (right - left) >> 1);
        int value = brackets[mid << 1];
        if (value == target) { return mid; }
        if (value < target) {
          left = mid + 1;
        } else {
          right = mid;
        }
      }
      return -1;
    }
  }

  private static final int[] ZERO_INTS = new int[0];

  private static final TokenType[] ZERO_TYPES = new TokenType[0];

  private static final Brackets EMPTY_BRACKETS = new Brackets(ZERO_INTS);

  private static final CssTokens EMPTY = new CssTokens(
      "", EMPTY_BRACKETS, ZERO_INTS, ZERO_TYPES);

  /**
   * Tokenizes according to section 4 of http://dev.w3.org/csswg/css-syntax/
   */
  private static final class Lexer {
    private final String css;
    private final StringBuilder sb;
    private int pos = 0;
    private final int cssLimit;

    private List<TokenType> tokenTypes = null;
    private int[] tokenBreaks = new int[128];
    private int tokenBreaksLimit = 0;

    /**
     * For each bracket, 2 ints: the token index of the bracket, and the token
     * index of its partner.
     * The array is sorted by the first int.
     * The second int is -1 when the bracket has not yet been closed.
     */
    private int[] brackets = ZERO_INTS;
    /**
     * The number of elements in {@link #brackets} that are valid.
     * {@code brackets[bracketsLimit:]} is zeroed space that the list can grow
     * into.
     */
    private int bracketsLimit = 0;
    /**
     * For each bracket that has not been closed, 2 ints:
     * its index in {@link #brackets} and the character of its close bracket
     * as an int.
     * This is a bracket stack so the array is sorted by the first int.
     */
    private int[] open = ZERO_INTS;
    /**
     * The number of elements in {@link #open} that are valid.
     * {@code open[openLimit:]} is garbage space that the stack can grow into.
     */
    private int openLimit = 0;

    Lexer(String css) {
      this.css = css;
      this.sb = new StringBuilder();
      this.cssLimit = css.length();
    }

    TokenType openBracket(char bracketChar) {
      char close;
      TokenType type;
      switch (bracketChar) {
        case '(': close = ')'; type = TokenType.LEFT_PAREN;  break;
        case '[': close = ']'; type = TokenType.LEFT_SQUARE; break;
        case '{': close = '}'; type = TokenType.LEFT_CURLY;  break;
        default:
          throw new AssertionError("Invalid open bracket " + bracketChar);
      }
      brackets = expandIfNecessary(brackets, bracketsLimit, 2);
      open = expandIfNecessary(open, openLimit, 2);
      open[openLimit++] = bracketsLimit;
      open[openLimit++] = close;
      brackets[bracketsLimit++] = tokenBreaksLimit;
      brackets[bracketsLimit++] = -1;
      sb.append(bracketChar);
      return type;
    }

    void closeBracket(char bracketChar) {
      int openLimitAfterClose = openLimit;
      do {
        if (openLimitAfterClose == 0) {
          // Drop an orphaned close bracket.
          breakOutput();
          return;
        }
        openLimitAfterClose -= 2;
      } while (bracketChar != open[openLimitAfterClose + 1]);

      // Make sure we've got space on brackets.
      int spaceNeeded = openLimit - openLimitAfterClose;
      brackets = expandIfNecessary(brackets, bracketsLimit, spaceNeeded);

      closeBrackets(openLimitAfterClose);
    }

    private void closeBrackets(int openLimitAfterClose) {
      int closeTokenIndex = tokenBreaksLimit;
      while (openLimit > openLimitAfterClose) {
        // Pop the stack.
        int closeBracket = open[--openLimit];
        int openBracketIndex = open[--openLimit];
        // Update open bracket to point to its partner.
        brackets[openBracketIndex + 1] = closeTokenIndex;
        // Emit the close bracket.
        brackets[bracketsLimit++] = closeTokenIndex;
        brackets[bracketsLimit++] = openBracketIndex;
        sb.appendCodePoint(closeBracket);
        closeTokenIndex++;
      }
    }

    CssTokens build() {
      // Close any still open brackets.
      {
        int startOfCloseBrackets = sb.length();
        closeBrackets(0);
        emitMergedTokens(startOfCloseBrackets, sb.length());
      }

      if (tokenTypes == null) { return EMPTY; }
      int[] bracketsTrunc = truncateOrShare(brackets, bracketsLimit);

      // Strip any trailing space off, since it may have been inserted by a
      // breakAfter call anyway.
      int cssEnd = sb.length();
      if (cssEnd > 0 && sb.charAt(cssEnd - 1) == ' ') {
if (DEBUG) System.err.println(
    "cssEnd=" + cssEnd + ", tokenBreaksLimit=" + tokenBreaksLimit + ", tokenTypes.size=" + tokenTypes.size());
        --cssEnd;
        tokenTypes.remove(--tokenBreaksLimit);
if (DEBUG) System.err.println(
    "cssEnd'=" + cssEnd + ", tokenBreaksLimit=" + tokenBreaksLimit + ", tokenTypes.size=" + tokenTypes.size());
      }
      String normalizedCss = sb.substring(0, cssEnd);

      // Store the last character on the tokenBreaksList to simplify finding the
      // end of a token.
      tokenBreaks = expandIfNecessary(tokenBreaks, tokenBreaksLimit, 1);
      tokenBreaks[tokenBreaksLimit++] = normalizedCss.length();

      int[] tokenBreaksTrunc = truncateOrShare(tokenBreaks, tokenBreaksLimit);
      TokenType[] tokenTypesArr = tokenTypes.toArray(ZERO_TYPES);

if (DEBUG) System.err.println("normalizedCss=" + normalizedCss);
if (DEBUG) System.err.println("tokenBreaks=" + Arrays.toString(tokenBreaksTrunc));
if (DEBUG) System.err.println("tokenTypes =" + Arrays.toString(tokenTypesArr));

      return new CssTokens(
          normalizedCss, new Brackets(bracketsTrunc),
          tokenBreaksTrunc, tokenTypesArr);
    }

    void lex() {
      // Fast-track no content.
      consumeIgnorable();
      sb.setLength(0);
      if (pos == cssLimit) { return; }

      String css = this.css;
      int cssLimit = this.cssLimit;
      int lastPos = pos - 1;
      while (pos < cssLimit) {
        assert pos > lastPos
            : "pos=" + pos + ", lastPos=" + lastPos + ", ch=" + css.charAt(pos);
        lastPos = pos;
if (DEBUG) System.err.println("Starting at " + pos);
        // SPEC: 4. Tokenization
        // The output of the tokenization step is a stream of zero
        // or more of the following tokens: <ident>, <function>,
        // <at-keyword>, <hash>, <string>, <bad-string>, <url>,
        // <bad-url>, <delim>, <number>, <percentage>,
        // <dimension>, <unicode-range>, <include-match>,
        // <dash-match>, <prefix-match>, <suffix-match>,
        // <substring-match>, <column>, <whitespace>, <CDO>,
        // <CDC>, <colon>, <semicolon>, <comma>, <[>, <]>,
        // <(>, <)>, <{>, and <}>.

        // IMPLEMENTS: 4.3 Consume a token
        char ch = css.charAt(pos);
        int startOfToken = pos;
        int startOfOutputToken = sb.length();
        final TokenType type;
        switch (ch) {
          case '\t': case '\n': case '\f': case '\r': case ' ':
            consumeIgnorable();
            type = TokenType.WHITESPACE;
            break;
          case '/': {
            char lookahead = pos + 1 < cssLimit ? css.charAt(pos + 1) : 0;
            if (lookahead == '/' || lookahead == '*') {
              consumeIgnorable();
              type = TokenType.WHITESPACE;
            } else {
              consumeDelim(ch);
              type = TokenType.DELIM;
            }
            break;
          }
          case '<':
            if (consumeIgnorable()) {  // <!--
              type = TokenType.WHITESPACE;
            } else {
              consumeDelim('<');
              type = TokenType.DELIM;
            }
            break;
          case '@':
            if (consumeAtKeyword()) {
              type = TokenType.AT;
            } else {
              consumeDelim(ch);
              type = TokenType.DELIM;
            }
            break;
          case '#': {
            sb.append('#');
            TokenType hashType = consumeHash();
            if (hashType != null) {
              type = hashType;
            } else {
              ++pos;
              type = TokenType.DELIM;
            }
            break;
          }
          case '"':
          case '\'':
            consumeString();
            type = TokenType.STRING;
            break;
          case 'U':
            // SPEC handle URL under "ident like token".
            if (consumeUnicodeRange()) {
              type = TokenType.UNICODE_RANGE;
            } else {
              type = consumeIdentOrUrlOrFunction();
            }
            break;
          case '0': case '1': case '2': case '3': case '4':
          case '5': case '6': case '7': case '8': case '9':
            type = consumeNumberOrPercentageOrDimension();
            break;
          case '+': case '-': case '.': {
            char lookahead = pos + 1 < cssLimit ? css.charAt(pos + 1) : 0;
if (DEBUG) System.err.println("ch=" + ch + ", lookahead=" + lookahead);
            if (isDecimal(lookahead)
                || (lookahead == '.' && pos + 2 < cssLimit
                    && isDecimal(css.charAt(pos + 2)))) {
              type = consumeNumberOrPercentageOrDimension();
            } else if (ch == '+') {
              consumeDelim(ch);
              type = TokenType.DELIM;
            } else if (ch == '-') {
              if (consumeIgnorable()) {  // -->
                type = TokenType.WHITESPACE;
              } else {
                type = consumeIdentOrUrlOrFunction();
              }
            } else if (isIdentPart(lookahead)) {
              // treat ".<IDENT>" as one token.
              sb.append('.');
              ++pos;
              consumeIdent();
              type = TokenType.DOT_IDENT;
            } else {
              consumeDelim('.');
              type = TokenType.DELIM;
            }
            break;
          }
          case ':': consumeDelim(ch); type = TokenType.COLON; break;
          case ';': consumeDelim(ch); type = TokenType.SEMICOLON; break;
          case ',': consumeDelim(ch); type = TokenType.COMMA; break;
          case '[': case '(': case '{':
            type = openBracket(ch);
            ++pos;
            break;
          case '}': case ')': case ']':
            closeBracket(ch);
            ++pos;
            // Use DELIM so that a later loop will split output into multiple
            // tokens since we may have inserted missing close brackets for
            // unclosed open brackets already on the stack.
            type = TokenType.DELIM;
            break;
          case '~': case '|': case '^': case '$': case '*': {
            char lookahead = pos + 1 < cssLimit ? css.charAt(pos + 1) : 0;
            if (lookahead == '=') {
              consumeMatch(ch);
              type = TokenType.MATCH;
            } else if (ch == '|' && lookahead == '|') {
              consumeColumn();
              type = TokenType.COLUMN;
            } else {
              consumeDelim(ch);
              type = TokenType.DELIM;
            }
            break;
          }
          case '_':
            type = consumeIdentOrUrlOrFunction();
            break;
          case '\\': {
            // Optimistically parse as an ident.
            TokenType identType = consumeIdentOrUrlOrFunction();
            if (identType == null) {
              ++pos;  // drop
              breakOutput();
              type = TokenType.WHITESPACE;
            } else {
              type = identType;
            }
            // TODO: handle case where "url" is encoded.
            break;
          }
          default:
            int chlower = ch | 32;
            if ('a' <= chlower && chlower <= 'z' || ch >= 0x80) {
              type = consumeIdentOrUrlOrFunction();
            } else if (ch > 0x20) {
              consumeDelim(ch);
              type = TokenType.DELIM;
            } else {  // Ignore.
              breakOutput();
              type = TokenType.WHITESPACE;
              ++pos;
            }
        }
        assert pos > startOfToken
            : "empty token at " + pos + ", ch0=" + css.charAt(startOfToken);
if (DEBUG) System.err.println("found `" + css.substring(startOfToken, pos) + "` -> `" + sb.substring(startOfOutputToken) + "`");
        int endOfOutputToken = sb.length();
        if (endOfOutputToken > startOfOutputToken) {
          if (type == TokenType.DELIM) {
            emitMergedTokens(startOfOutputToken, endOfOutputToken);
          } else {
            emitToken(type, startOfOutputToken);
          }
        }
      }
    }

    private void emitMergedTokens(int start, int end) {
      // Handle breakOutput and merging of output tokens.
      for (int e = start; e < end; ++e) {
        TokenType delimType;
        switch (sb.charAt(e)) {
          case ' ': delimType = TokenType.WHITESPACE;   break;
          case '}': delimType = TokenType.RIGHT_CURLY;  break;
          case ')': delimType = TokenType.RIGHT_PAREN;  break;
          case ']': delimType = TokenType.RIGHT_SQUARE; break;
          default : delimType = TokenType.DELIM;        break;
        }
        emitToken(delimType, e);
      }
    }

    private void emitToken(TokenType type, int startOfOutputToken) {
      if (tokenBreaksLimit == 0
          || tokenBreaks[tokenBreaksLimit - 1] != startOfOutputToken) {
        if (tokenTypes == null) { tokenTypes = new ArrayList<TokenType>(); }
        tokenBreaks = expandIfNecessary(tokenBreaks, tokenBreaksLimit, 1);
        tokenBreaks[tokenBreaksLimit++] = startOfOutputToken;
        tokenTypes.add(type);
      }
    }

    private void consumeDelim(char ch) {
      sb.append(ch);
      switch (ch) {
        case '~': case '|': case '^': case '$': case '\\': case '#':
        case '.': case '+': case '-': case '@': case '/':
          sb.append(' ');
          break;
        default:
          break;
      }
      ++pos;
    }

    private boolean consumeIgnorable() {
      String css = this.css;
      int cssLimit = this.cssLimit;
      int posBefore = pos;
      while (pos < cssLimit) {
        char ch = css.charAt(pos);
if (DEBUG) System.err.println("possibly ignorable " + ch + " at " + pos);
        if (ch <= 0x20) {
          ++pos;
        } else if (pos + 1 == cssLimit) {
          break;
        } else if (ch == '/') {
          char next = css.charAt(pos + 1);
if (DEBUG) System.err.println("Found /, next=" + next);
          if (next == '*') {
            pos += 2;
if (DEBUG) System.err.println("Looking for end of /* at " + pos);
            while (pos < cssLimit) {
              int ast = css.indexOf('*', pos);
if (DEBUG) System.err.println("ast=" + ast);
              if (ast < 0) {
                pos = cssLimit;  // Unclosed /* comment */
                break;
              } else {
                // Advance over a run of '*'s.
                pos = ast + 1;
                while (pos < cssLimit && css.charAt(pos) == '*') {
                  ++pos;
                }
if (DEBUG) System.err.println("Looking for / at " + pos);
                if (pos < cssLimit && css.charAt(pos) == '/') {
                  ++pos;
                  break;
                }
              }
            }
if (DEBUG) System.err.println("Finished */ at " + pos);
          } else if (next == '/') {  // Non-standard but widely supported
            while (++pos < cssLimit) {
              if (isLineTerminator(css.charAt(pos))) { break; }
            }
          } else {
            break;
          }
        } else if (ch == '<') {
          if (pos + 3 < cssLimit
              && '!' == css.charAt(pos + 1)
              && '-' == css.charAt(pos + 2)
              && '-' == css.charAt(pos + 3)) {
            pos += 4;
          } else {
            break;
          }
        } else if (ch == '-') {
          if (pos + 2 < cssLimit
              && '-' == css.charAt(pos + 1)
              && '>' == css.charAt(pos + 2)) {
            pos += 3;
          } else {
            break;
          }
        } else {
          break;
        }
      }
      if (pos == posBefore) {
        return false;
      } else {
        breakOutput();
        return true;
      }
    }

    private void breakOutput() {
      int last = sb.length() - 1;
      if (last < 0 || sb.charAt(last) != ' ') { sb.append(' '); }
    }

    private void consumeColumn() {
      pos += 2;
      sb.append("||");
    }

    private void consumeMatch(char ch) {
      pos += 2;
      sb.append(ch).append('=');
    }

    private void consumeIdent() {
      String css = this.css;
      int cssLimit = this.cssLimit;
      int last = -1;
      while (pos < cssLimit) {
        int posBefore = pos;

        int decoded = css.charAt(pos);
        if (decoded == '\\') {
          decoded = consumeAndDecodeEscapeSequence();
        } else {
          ++pos;
        }

        if (isIdentPart(decoded)) {
          if (decoded >= 0x80) {
            encodeCharOntoOutput(decoded, last);
          } else {
            sb.append((char) decoded);
          }
          last = decoded;
        } else {
          pos = posBefore;
          break;
        }
      }
    }

    private boolean consumeAtKeyword() {
      assert css.charAt(pos) == '@';
      int bufferLengthBeforeWrite = sb.length();
      sb.append('@');
      int posBeforeKeyword = ++pos;
      consumeIdent();
      if (pos == posBeforeKeyword) {
        --pos;  // back up over '@'
        sb.setLength(bufferLengthBeforeWrite);  // Unwrite the '@'
        return false;
      } else {
        return true;
      }
    }


    private int consumeAndDecodeEscapeSequence() {
      String css = this.css;
      int cssLimit = this.cssLimit;
      assert css.charAt(pos) == '\\';
      if (pos + 1 >= cssLimit) { return -1; }
      char esc = css.charAt(pos + 1);
      if (isLineTerminator(esc)) { return -1; }
      int escLower = esc | 32;
      if (('0' <= esc && esc <= '9')
          || ('a' <= escLower && escLower <= 'f')) {
        int hexValue = 0;
        int hexStart = pos + 1;
        int hexLimit = Math.min(pos + 7, cssLimit);
        int hexEnd = hexStart;
        do {
          hexValue = (hexValue << 4)
              | (esc <= '9' ? esc - '0' : escLower - ('a' - 10));
          ++hexEnd;
          if (hexEnd == hexLimit) { break; }
          esc = css.charAt(hexEnd);
          escLower = esc | 32;
        } while (('0' <= esc && esc <= '9')
                 || ('a' <= escLower && escLower <= 'f'));
        if (hexValue > Character.MAX_CODE_POINT) {
          return -1;
        }
        pos = hexEnd;
        if (pos + 1 < cssLimit) {
          // A sequence of hex digits can be followed by a space that allows
          // so that code-point U+A followed by the letter 'b' can be rendered
          // as "\a b" since "\ab" specifies the single code-point U+AB.
          char next = css.charAt(pos + 1);
          if (next == ' ' || isLineTerminator(next)) {
            ++pos;
          }
        }
        return hexValue;
      }
      pos += 2;
      return esc;
    }

    private static final long HEX_ENCODED_BITMASK =
        (1L << 0) | LINE_TERMINATOR_BITMASK
        | (1L << '"') | (1L << '\'') | (1L << '&') | (1L << '<') | (1L << '>');
    private static boolean isHexEncoded(int codepoint) {
      return 0 <= codepoint && codepoint < 63
          && 0 != ((1L << codepoint) & HEX_ENCODED_BITMASK);
    }

    private void encodeCharOntoOutput(int codepoint, int last) {
if (DEBUG) System.err.println("codepoint=" + Integer.toString(codepoint, 16) + ", last=" + Integer.toString(last, 16));
      switch (codepoint) {
        case '\\': sb.append("\\\\"); break;
        case '\0': sb.append("\\0");  break;
        case '\n': sb.append("\\a");  break;
        case '\f': sb.append("\\c");  break;
        case '\r': sb.append("\\d");  break;
        case '\"': sb.append("\\22"); break;
        case '&':  sb.append("\\26"); break;
        case '\'': sb.append("\\27"); break;
        case '<':  sb.append("\\3c"); break;
        case '>':  sb.append("\\3e"); break;
        // The set of escapes above that end with a hex digit must appear in
        // HEX_ENCODED_BITMASK.
        case '-':
          if (last == '-') {
            sb.append('\\');
          }
          sb.append('-');
          break;
        // TODO: maybe encode a decimal digit after a '-'.
        default:
          if (isHexEncoded(last)
              // We need to put a space after a trailing hex digit if the
              // next encoded character on the output would be another hex
              // digit or a space character.  The other space characters
              // are handled above.
              && (codepoint == ' '
                  || ('0' <= codepoint && codepoint <= '9')
                  || ('a' <= (codepoint | 32) && (codepoint | 32) <= 'f'))) {
if (DEBUG) System.err.println("last '" + new StringBuilder().appendCodePoint(last) + "' is hex-encoded");
            sb.append(' ');
          }
          sb.appendCodePoint(codepoint);
      }
    }

    private TokenType consumeNumberOrPercentageOrDimension() {
if (DEBUG) System.err.println("Consuming number");
      String css = this.css;
      int cssLimit = this.cssLimit;
      boolean isZero = true;
      int intStart = pos;
      if (intStart < cssLimit) {
        char ch = css.charAt(intStart);
        if (ch == '-' || ch == '+') {
          ++intStart;
        }
      }
if (DEBUG) System.err.println("\tintStart=" + intStart);
      // Find the integer part after any sign.
      int intEnd = intStart;
      for (; intEnd < cssLimit; ++intEnd) {
        char ch = css.charAt(intEnd);
        if (!('0' <= ch && ch <= '9')) { break; }
        if (ch != '0') { isZero = false; }
      }
if (DEBUG) System.err.println("\tintEnd=" + intEnd);
      // Find a fraction like ".5" or ".".
      int fractionStart = intEnd;
      int fractionEnd = fractionStart;
      if (fractionEnd < cssLimit && '.' == css.charAt(fractionEnd)) {
        ++fractionEnd;
        for (; fractionEnd < cssLimit; ++fractionEnd) {
          char ch = css.charAt(fractionEnd);
          if (!('0' <= ch && ch <= '9')) { break; }
          if (ch != '0') { isZero = false; }
        }
      }
if (DEBUG) System.err.println("\tfractionStart=" + fractionStart);
if (DEBUG) System.err.println("\tfractionEnd=" + fractionEnd);
      int exponentStart = fractionEnd;
      int exponentIntStart = exponentStart;
      int exponentEnd = exponentStart;
      boolean isExponentZero = true;
      if (exponentStart < cssLimit && 'e' == (css.charAt(exponentStart) | 32)) {
        // 'e' and 'e' in "5e-f" for a
        exponentEnd = exponentStart + 1;
        if (exponentEnd < cssLimit) {
          char ch = css.charAt(exponentEnd);
          if (ch == '+' || ch == '-') { ++exponentEnd; }
        }
        exponentIntStart = exponentEnd;
        for (; exponentEnd < cssLimit; ++exponentEnd) {
          char ch = css.charAt(exponentEnd);
          if (!('0' <= ch && ch <= '9')) { break; }
          if (ch != '0') { isExponentZero = false; }
        }
        // Since
        //    dimension := <number> <ident>
        // the below are technically valid dimensions even though they appear
        // to have incomplete exponents:
        //    5e
        //    5ex
        //    5e-
        if (exponentEnd == exponentIntStart) {  // Incomplete exponent.
          exponentIntStart = exponentEnd = exponentStart;
          isExponentZero = true;
        }
      }
if (DEBUG) System.err.println("\texponentStart=" + exponentStart);
if (DEBUG) System.err.println("\texponentIntStart=" + exponentIntStart);
if (DEBUG) System.err.println("\texponentEnd=" + exponentEnd);

      int unitStart = exponentEnd;
      // Skip over space between number and unit.
      // Many user-agents allow "5 ex" instead of "5ex".
      while (unitStart < cssLimit) {
        char ch = css.charAt(unitStart);
        if (ch == ' ' || isLineTerminator(ch)) {
          ++unitStart;
        } else {
          break;
        }
      }

int sbStart = sb.length();
if (DEBUG) System.err.println("\tCP0 : " + sb.substring(sbStart));

      // Normalize the number onto the buffer.
      // We will normalize and unit later.
      // Skip the sign if it is positive.
      if (intStart != pos && '-' == css.charAt(pos) && !isZero) {
        sb.append('-');
      }
      if (isZero) {
        sb.append('0');
      } else {
        // Strip leading zeroes from the integer and exponent and trailing
        // zeroes from the fraction.
        while (intStart < intEnd && css.charAt(intStart) == '0') { ++intStart; }
if (DEBUG) System.err.println("\tintStart <- " + intStart);
        while (fractionEnd > fractionStart
               && css.charAt(fractionEnd - 1) == '0') {
          --fractionEnd;
        }
if (DEBUG) System.err.println("\tfractionEnd <- " + fractionEnd);
        if (intStart == intEnd) {
          sb.append('0');  // .5 -> 0.5
        } else {
          sb.append(css, intStart, intEnd);
        }
        if (fractionEnd > fractionStart + 1) {  // 5. -> 5; 5.0 -> 5
          sb.append(css, fractionStart, fractionEnd);
        }
if (DEBUG) System.err.println("\tCP1 : " + sb.substring(sbStart) + " ; sbStart=" + sbStart);
        if (!isExponentZero) {
          sb.append('e');
          // 1e+1 -> 1e1
          if ('-' == css.charAt(exponentIntStart - 1)) { sb.append('-'); }
          while (exponentIntStart < exponentEnd
                 && css.charAt(exponentIntStart) == '0') {
            ++exponentIntStart;
          }
if (DEBUG) System.err.println("\texponentIntStart <- " + exponentIntStart);
          sb.append(css, exponentIntStart, exponentEnd);
if (DEBUG) System.err.println("\tCP2 : " + sb.substring(sbStart));
        }
      }
if (DEBUG) System.err.println("\tCP3 : " + sb.substring(sbStart));

      int unitEnd;
      TokenType type;
      if (unitStart < cssLimit && '%' == css.charAt(unitStart)) {
        unitEnd = unitStart + 1;
        type = TokenType.PERCENTAGE;
        sb.append('%');
      } else {
        // The grammar says that any identifier following a number is a unit.
        int bufferBeforeUnit = sb.length();
        pos = unitStart;
        consumeIdent();
        int bufferAfterUnit = sb.length();
        if (unitStart == exponentEnd  // No intervening space
            || isWellKnownUnit(sb, bufferBeforeUnit, bufferAfterUnit)) {
          unitEnd = pos;
          // 3IN -> 3in
          for (int i = bufferBeforeUnit; i < bufferAfterUnit; ++i) {
            char ch = sb.charAt(i);
            if ('A' <= ch && ch <= 'Z') { sb.setCharAt(i, (char) (ch | 32)); }
          }
        } else {
          unitEnd = unitStart = exponentEnd;
          sb.setLength(bufferBeforeUnit);
        }
        type = unitStart == unitEnd ? TokenType.NUMBER : TokenType.DIMENSION;
      }
      pos = unitEnd;
if (DEBUG) System.err.println("\tunitStart=" + unitStart);
if (DEBUG) System.err.println("\tunitEnd=" + unitEnd);
if (DEBUG) System.err.println("\tCP4 : " + sb.substring(sbStart));
      return type;
    }

    private void consumeString() {
      String css = this.css;
      int cssLimit = this.cssLimit;

      char delim = css.charAt(pos);
      ++pos;
      sb.append('\'');
      int last = -1;
      while (pos < cssLimit) {
        char ch = css.charAt(pos);
        if (ch == delim) {
          ++pos;
          break;
        }
        if (isLineTerminator(ch)) { break; } // bad-string
        int decoded = ch;
        if (ch == '\\') {
          decoded = consumeAndDecodeEscapeSequence();
        } else {
          ++pos;
        }
        encodeCharOntoOutput(decoded, last);
        last = decoded;
      }
      sb.append('\'');
    }

    private @Nullable TokenType consumeHash() {
      assert css.charAt(pos) == '#';
      ++pos;
      int beforeIdent = pos;
      consumeIdent();
      if (pos == beforeIdent) {
        pos = beforeIdent - 1;
        return null;
      }
      for (int i = beforeIdent; i < pos; ++i) {
        char ch = css.charAt(i);
        if (!(('0' <= ch && ch <= '9')
              || ('a' <= (ch | 32) && (ch | 32) <= 'f'))) {
          return TokenType.HASH_ID;
        }
      }
      return TokenType.HASH_UNRESTRICTED;
    }

    private boolean consumeUnicodeRange() {
      String css = this.css;
      int cssLimit = this.cssLimit;

      int start = pos;
      assert (css.charAt(pos) | 32) == 'u';
      ++pos;
      if (pos == cssLimit && css.charAt(pos) != '+') {
        pos = start;
        return false;
      }
      int numStartDigits = 0;
      while (pos < cssLimit && numStartDigits < 6) {
        char chLower = css.charAt(pos);
        if (('0' <= chLower && chLower <= '9')
            || ('a' <= chLower && chLower <= 'f')) {
          ++numStartDigits;
          ++pos;
        } else {
          break;
        }
      }
      boolean hasQmark = false;
      while (pos < cssLimit && numStartDigits < 6 && css.charAt(pos) == '?') {
        hasQmark = true;
        ++numStartDigits;
        ++pos;
      }
      if (numStartDigits == 0) {
        pos = start;
        return false;
      }
      if (!hasQmark && pos < cssLimit && css.charAt(pos) == '-') {
        ++pos;
        int numEndDigits = 0;
        while (pos < cssLimit && numEndDigits < 6) {
          char chLower = css.charAt(pos);
          if (('0' <= chLower && chLower <= '9')
              || ('a' <= chLower && chLower <= 'f')) {
            ++numEndDigits;
            ++pos;
          } else {
            break;
          }
        }
        if (numEndDigits == 0) { --pos; }  // Back up over '-'
      }
      return true;
    }

    private @Nullable TokenType consumeIdentOrUrlOrFunction() {
      int bufferStart = sb.length();
      consumeIdent();
      boolean parenAfter = pos < cssLimit && css.charAt(pos) == '(';
      if (sb.length() - bufferStart == 3
          && 'u' == (sb.charAt(bufferStart) | 32)
          && 'r' == (sb.charAt(bufferStart + 1) | 32)
          && 'l' == (sb.charAt(bufferStart + 2) | 32)) {
        if (parenAfter && consumeUrlValue()) {
          sb.setCharAt(bufferStart, 'u');
          sb.setCharAt(bufferStart + 1, 'r');
          sb.setCharAt(bufferStart + 2, 'l');
          return TokenType.URL;
        } else {
          sb.setLength(bufferStart);
          breakOutput();
          return TokenType.WHITESPACE;
        }
      } else if (parenAfter) {
        openBracket('(');
        ++pos;
        return TokenType.FUNCTION;
      } else {
        return TokenType.IDENT;
      }
    }

    private boolean consumeUrlValue() {
      String css = this.css;
      int cssLimit = this.cssLimit;
      if (pos == cssLimit || css.charAt(pos) != '(') { return false; }
      ++pos;
      // skip space.
      for (; pos < cssLimit; ++pos) {
        char ch = css.charAt(pos);
        if (ch != ' ' && !isLineTerminator(ch)) { break; }
      }
      // Find the value.
      int delim;
      if (pos < cssLimit) {
        char ch = pos < cssLimit ? css.charAt(pos) : '\0';
        if (ch == '"' || ch == '\'') {
          delim = ch;
          ++pos;
        } else {
          delim = '\0';
        }
      } else {
        return false;
      }
      sb.append("('");
      while (pos < cssLimit) {
        int decoded = css.charAt(pos);
        if (delim != 0) {
          if (decoded == delim) { break; }
        } else if (decoded <= ' ' || decoded == ')') {
          break;
        }
        if (decoded == '\\') {
          decoded = consumeAndDecodeEscapeSequence();
        } else {
          ++pos;
        }
        // Any character not in the RFC 3986 safe set is %-encoded.
        if (decoded > 0x80 || URL_SAFE[decoded]) {
          sb.appendCodePoint(decoded);
        } else {
          sb.append('%')
            .append(HEX_DIGITS[(decoded >>> 4) & 0xf])
            .append(HEX_DIGITS[(decoded >>> 0) & 0xf]);
        }
      }

      // skip space.
      for (; pos < cssLimit; ++pos) {
        char ch = css.charAt(pos);
        if (ch != ' ' && !isLineTerminator(ch)) { break; }
      }
      if (pos < cssLimit && css.charAt(pos) == ')') {
        ++pos;
      } else {
        // broken-url
      }
      sb.append("')");
      return true;
    }
  }

  private static final boolean isIdentPart(int cp) {
    return cp >= 0x80 || IDENT_PART_ASCII[cp];
  }

  private static final boolean isDecimal(char ch) {
    return '0' <= ch && ch <= '9';
  }

  private static final boolean[] IDENT_PART_ASCII = new boolean[128];
  static {
    for (int i = '0'; i <= '9'; ++i) { IDENT_PART_ASCII[i] = true; }
    for (int i = 'A'; i <= 'Z'; ++i) { IDENT_PART_ASCII[i] = true; }
    for (int i = 'a'; i <= 'z'; ++i) { IDENT_PART_ASCII[i] = true; }
    IDENT_PART_ASCII['_'] = true;
    IDENT_PART_ASCII['-'] = true;
  }

  private static final int LINE_TERMINATOR_BITMASK =
      (1 << '\n') | (1 << '\r') | (1 << '\f');

  private static boolean isLineTerminator(char ch) {
    return ch < 0x20 && 0 != (LINE_TERMINATOR_BITMASK & (1 << ch));
  }

  private static int[] expandIfNecessary(int[] arr, int limit, int needed) {
    int neededLength = limit + needed;
    int length = arr.length;
if (DEBUG) System.err.println("limit=" + limit + ", needed=" + needed + ", length=" + length);
    if (length >= neededLength) { return arr; }
    int[] newArr = new int[Math.max(16, Math.max(neededLength, length * 2))];
    System.arraycopy(arr, 0, newArr, 0, limit);
if (DEBUG) System.err.println("\texpanded to " + newArr.length);
    return newArr;
  }

  private static int[] truncateOrShare(int[] arr, int limit) {
    if (limit == 0) { return ZERO_INTS; }
    if (limit == arr.length) {
      return arr;
    }
    int[] trunc = new int[limit];
    System.arraycopy(arr, 0, trunc, 0, limit);
    return trunc;
  }

  private static final int LENGTH_UNIT_TYPE = 0;
  private static final int ANGLE_UNIT_TYPE = 1;
  private static final int TIME_UNIT_TYPE = 2;
  private static final int FREQUENCY_UNIT_TYPE = 3;
  private static final int RESOLUTION_UNIT_TYPE = 4;

  /**
   * See http://dev.w3.org/csswg/css-values/#lengths and
   *     http://dev.w3.org/csswg/css-values/#other-units
   */
  private static final Trie UNIT_TRIE = new Trie(
      ImmutableMap.<String, Integer>builder()
        .put("em", LENGTH_UNIT_TYPE)
        .put("ex", LENGTH_UNIT_TYPE)
        .put("ch", LENGTH_UNIT_TYPE)  // Width of zero character
        .put("rem", LENGTH_UNIT_TYPE)  // Root element font-size
        .put("vh", LENGTH_UNIT_TYPE)
        .put("vw", LENGTH_UNIT_TYPE)
        .put("vmin", LENGTH_UNIT_TYPE)
        .put("vmax", LENGTH_UNIT_TYPE)
        .put("px", LENGTH_UNIT_TYPE)
        .put("mm", LENGTH_UNIT_TYPE)
        .put("cm", LENGTH_UNIT_TYPE)
        .put("in", LENGTH_UNIT_TYPE)
        .put("pt", LENGTH_UNIT_TYPE)
        .put("pc", LENGTH_UNIT_TYPE)
        .put("deg", ANGLE_UNIT_TYPE)
        .put("rad", ANGLE_UNIT_TYPE)
        .put("grad", ANGLE_UNIT_TYPE)
        .put("turn", ANGLE_UNIT_TYPE)
        .put("s", TIME_UNIT_TYPE)
        .put("ms", TIME_UNIT_TYPE)
        .put("hz", FREQUENCY_UNIT_TYPE)
        .put("khz", FREQUENCY_UNIT_TYPE)
        .put("dpi", RESOLUTION_UNIT_TYPE)
        .put("dpcm", RESOLUTION_UNIT_TYPE)
        .put("dppx", RESOLUTION_UNIT_TYPE)
        .build());

  private static boolean isWellKnownUnit(StringBuilder sb, int start, int end) {
    if (start == end) { return false; }
    Trie t = UNIT_TRIE;
    for (int i = start; i < end; ++i) {
      char ch = sb.charAt(i);
      t = t.lookup('A' <= ch && ch <= 'Z' ? (char) (ch | 32) : ch);
      if (t == null) { return false; }
    }
    return t.isTerminal();
  }


  private static final boolean[] URL_SAFE = new boolean[128];
  static {
    // From RFC 3986
    // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
    for (int i = 'A'; i <= 'Z'; ++i) { URL_SAFE[i] = true; }
    for (int i = 'a'; i <= 'z'; ++i) { URL_SAFE[i] = true; }
    for (int i = '0'; i <= '9'; ++i) { URL_SAFE[i] = true; }
    URL_SAFE['-'] = true;
    URL_SAFE['.'] = true;
    URL_SAFE['_'] = true;
    URL_SAFE['~'] = true;
    // gen-delims  = ":" / "/" / "?" / "#" / "[" / "]" / "@"
    URL_SAFE[':'] = true;
    URL_SAFE['/'] = true;
    URL_SAFE['?'] = true;
    URL_SAFE['#'] = true;
    URL_SAFE['['] = true;
    URL_SAFE[']'] = true;
    URL_SAFE['@'] = true;
    // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
    //             / "*" / "+" / "," / ";" / "="
    URL_SAFE['!'] = true;
    URL_SAFE['$'] = true;
    URL_SAFE['&'] = true;
    // Only used in obsolete mark rule and special in unquoted URLs or comment
    // delimiters.
    // URL_SAFE['\''] = true;
    // URL_SAFE['('] = true;
    // URL_SAFE[')'] = true;
    // URL_SAFE['*'] = true;
    URL_SAFE['+'] = true;
    URL_SAFE[','] = true;
    URL_SAFE[';'] = true;
    URL_SAFE['='] = true;
  }

  private static final char[] HEX_DIGITS = {
    '0', '1', '2', '3',
    '4', '5', '6', '7',
    '8', '9', 'a', 'b',
    'c', 'd', 'e', 'f'
  };
}
