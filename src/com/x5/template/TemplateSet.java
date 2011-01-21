package com.x5.template;

import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.File;
import java.util.Hashtable;
import java.util.HashSet;

// Project Title: Chunk
// Description: Template Util
// Copyright: Copyright (c) 2007
// Author: Tom McClure

/**
 * TemplateSet is a Chunk "factory" and an easy way to parse
 * template files into Strings.  The default caching behavior is
 * great for high traffic applications.
 *
 * <PRE>
 * // Dynamic content in templates is marked with {~...}
 * //
 * // Previously the syntax was {$...} but then I had a project where
 * // some of the templates were shared by a perl script and I got
 * // tired of escaping the $ signs in inline templates in my perl code.
 * //
 * // Before that there was an escaped-HTML-inspired syntax which
 * // looked like &tag_...; since this was thought to be most
 * // compatible with HTML editors like DreamWeaver but it was hard
 * // to read and as it turned out DreamWeaver choked on it.
 * //
 * // See TemplateSet.convertTags(...) and .convertToMyTags(...) for
 * // quick search-and-replace routines for updating tag syntax on
 * // old templates...
 * //
 * // ...Or, if an entire template set uses another syntax just call
 * // .setTagBoundaries("{$", "}") on the TemplateSet object before
 * // you use it to make any chunks.  All subsequent chunks made from
 * // that TemplateSet will find and replace the {$...} style tags.
 * //
 * // Be careful: for interoperability you will need to call
 * // .setTagBoundaries() individually on any blank chunks you make
 * // without the aid of the TemplateSet object, ie with the Chunk
 * // constructor -- better to use the no-arg .makeChunk() method of
 * // the TemplateSet instead, since that automatically coerces the
 * // blank Chunk's tag boundaries correctly.
 * //
 * ///// In summary, for back-compatibility:
 * //
 * // TemplateSet templates = new TemplateSet(...);
 * // templates.setTagBoundaries("{$", "}");
 * // ...
 * //
 * // *** (A) BAD ***
 * // ...
 * // Chunk c = new Chunk(); // will only explode tags like default {~...}
 * //
 * // *** (B) NOT AS BAD ***
 * // ...
 * // Chunk c = new Chunk();
 * // c.setTagBoundaries("{$", "}"); // manually match tag format :(
 * //
 * // *** (C) BEST ***
 * // ...
 * // Chunk c = templates.makeChunk(); // inherits TemplateSet's tag format :)
 * //
 * </PRE>
 *
 * Copyright: Copyright (c) 2003<BR>
 * Company: <A href="http://www.x5software.com/">X5 Software</A><BR>
 * Updates: <A href="http://www.dagblastit.com/">www.dagblastit.com</A><BR>
 *
 * @author Tom McClure
 * @version 2.0
 */

public class TemplateSet implements ContentSource, ChunkFactory
{

    public static String DEFAULT_TAG_START = "{~";
    public static String DEFAULT_TAG_END = "}";
    public static final String MACRO_START = "{*";
    public static final String MACRO_NAME_END = "}";
    public static final String MACRO_END = "{*}";
    public static final String MACRO_LET = "{=";
    public static final String MACRO_LET_END = "}";
    public static final String INCLUDE_SHORTHAND = "{+";
    public static final String PROTOCOL_SHORTHAND = "{^";

    private static final long oneMinuteInMillis = 60 * 1000;
    // having a minimum cache time of five seconds improves
    // performance by avoiding typical multiple parses of a
    // file for its subtemplates in a short span of code.
    private static final long MIN_CACHE = 5 * 1000;
    private static final String SUB_START = "{#";
    private static final String SUB_NAME_END = "}";
    private static final String SUB_END = "{#}";
    private static final String COMMENT_START = "{!--";
    private static final String COMMENT_END = "--}";
    private static final int DEFAULT_REFRESH = 15; // minutes
    private static final String DEFAULT_EXTENSION = "html";

    private Hashtable<String,StringBuilder> cache = new Hashtable<String,StringBuilder>();
    private Hashtable<String,Long> cacheFetch = new Hashtable<String,Long>();
    private int dirtyInterval = DEFAULT_REFRESH; // minutes
    private String defaultExtension = DEFAULT_EXTENSION;
    private String tagStart = DEFAULT_TAG_START;
    private String tagEnd = DEFAULT_TAG_END;
    private String templatePath = System.getProperty("templateset.folder","");
    
    private boolean prettyFail = true;

    public TemplateSet() {}

    /**
     * Makes a template "factory" which reads in template files from the
     * file system in the templatePath folder.  Caches for 15 minutes.
     * Assumes .html is default file extension.
     * @param templatePath folder where template files are located.
     */
    public TemplateSet(String templatePath)
    {
        this(templatePath, DEFAULT_EXTENSION, DEFAULT_REFRESH);
    }

    /**
     * Makes a template "factory" which reads in template files from the
     * file system in the templatePath folder.  Caches for refreshMins.
     * Uses "extensions" for the default file extension (do not include dot).
     * @param templatePath folder where template files are located.
     * @param extensions appends dot plus this String to a template name stub to find template files.
     * @param refreshMins returns template from cache unless this many minutes have passed.
     */
    public TemplateSet(String templatePath, String extension, int refreshMins)
    {
        if (templatePath != null) {
            // ensure trailing fileseparator
            char lastChar = templatePath.charAt(templatePath.length()-1);
            char fs = System.getProperty("file.separator").charAt(0);
            if (lastChar != '\\' && lastChar != '/' && lastChar != fs) {
                templatePath += fs;
            }
            this.templatePath = templatePath;
        }
        this.dirtyInterval = refreshMins;
        this.defaultExtension = extension;
    }

    /**
     * Retrieve as String the template specified by name.
     * If name contains one or more dots it is assumed that the template
     * definition is nested inside another template.  Everything up to the
     * first dot is part of the filename (appends the DEFAULT extension to
     * find the file) and everything after refers to a location within the
     * file where the template contents are defined.
     * <P>
     * For example: String myTemplate = templateSet.get("outer_file.inner_template");
     * <P>
     * will look for {#inner_template}bla bla bla{#} inside the file
     * "outer_file.html" or "outer_file.xml" ie whatever your TemplateSet extension is.
     * @param name the location of the template definition.
     * @return the template definition from the file as a String
     */
    public String getSnippet(String name)
    {
        return getSnippet(name, defaultExtension);
    }

    public String fetch(String name)
    {
        return getSnippet(name);
    }

    public String getProtocol()
    {
        return "include";
    }

    /**
     * Retrieve as String the template specified by name and extension.
     * If name contains one or more dots it is assumed that the template
     * definition is nested inside another template.  Everything up to the
     * first dot is part of the filename (appends the PASSED extension to
     * find the file) and everything after refers to a location within the
     * file where the template contents are defined.
     * @param name the location of the template definition.
     * @param extension the nonstandard extension which forms the template filename.
     * @return the template definition from the file as a String
     */
    public String getSnippet(String name, String extension)
    {
    	return _get(name, extension, this.prettyFail);
    }
    
    private String _get(String name, String extension, boolean prettyFail)
    {
        //long now = System.currentTimeMillis();

        StringBuilder template = getFromCache(name, extension);
        String filename = null;

        // if not in cache, parse file and place all pieces in cache
        if (template == null) {
            String stub = truncateNameToStub(name);

            filename = getTemplatePath(name,extension);
            char fs = System.getProperty("file.separator").charAt(0);
            filename.replace('\\',fs);
            filename.replace('/',fs);
            try {
                File templateFile = new File(filename);
                if (templateFile.exists()) {
                    FileReader in = new FileReader(templateFile);
                    BufferedReader brTemp = new BufferedReader(in);
                    readAndCacheTemplate(stub,extension,brTemp);
                    in.close();
                    template = getFromCache(name, extension);
                }
            } catch (java.io.IOException e) {
            	if (!prettyFail) return null;
            	
                template = new StringBuilder("[error fetching ");
                template.append(extension);
                template.append(" template '");
                template.append(name);
                template.append("']<!-- ");
                StringWriter w = new StringWriter();
                e.printStackTrace(new PrintWriter(w));
                template.append(w.toString());
                template.append(" -->");
            }
        }

        if (template == null) {
        	if (prettyFail) {
	            template = new StringBuilder();
	            template.append("[");
	            template.append(extension);
	            template.append(" template '");
	            template.append(name);
	            template.append("' not found]<!-- looked in [");
	            template.append(filename);
	            template.append("] -->");
        	} else {
        		return null;
        	}
        }

        return template.toString();
    }

    /**
     * Creates a Chunk with no starter template and sets its tag boundary
     * markers to match the other templates in this set.  The Chunk will need
     * to obtain template pieces via its .append() method.
     * @return blank Chunk.
     */
    public Chunk makeChunk()
    {
        Chunk c = new Chunk();
        c.setTagBoundaries(tagStart,tagEnd);
        c.setMacroLibrary(this,this);
        shareContentSources(c);
        return c;
    }

    /**
     * Creates a Chunk with a starting template.  If templateName contains one
     * or more dots it is assumed that the template definition is nested inside
     * another template.  Everything up to the first dot is part of the filename
     * (appends the DEFAULT extension to find the file) and everything after
     * refers to a location within the file where the template contents are
     * defined.
     *
     * @param templateName the location of the template definition.
     * @return a Chunk pre-initialized with a snippet of template.
     */
    public Chunk makeChunk(String templateName)
    {
        Chunk c = new Chunk();
        c.setTagBoundaries(tagStart,tagEnd);
        c.setMacroLibrary(this,this);
        c.append( getSnippet(templateName) );
        shareContentSources(c);
        return c;
    }

    /**
     * Creates a Chunk with a starting template.  If templateName contains one
     * or more dots it is assumed that the template definition is nested inside
     * another template.  Everything up to the first dot is part of the filename
     * (appends the PASSED extension to find the file) and everything after
     * refers to a location within the file where the template contents are
     * defined.
     *
     * @param templateName the location of the template definition.
     * @param extension the nonstandard extension which forms the template filename.
     * @return a Chunk pre-initialized with a snippet of template.
     */
    public Chunk makeChunk(String templateName, String extension)
    {
        Chunk c = new Chunk();
        c.setTagBoundaries(tagStart,tagEnd);
        c.setMacroLibrary(this,this);
        c.append( getSnippet(templateName, extension) );
        shareContentSources(c);
        return c;
    }

    /**
     * Default tag boundaries are "{~" and "}" but other styles may be used.
     * Once this is called all calls to makeChunk will use the new values.
     * @param tagStart the String that marks the beginning of a tag
     * @param tagEnd the String that marks the end of tag
     */
    public void setTagBoundaries(String tagStart, String tagEnd)
    {
        this.tagStart = tagStart;
        this.tagEnd = tagEnd;
    }

    /**
     * For dynamic template formation, this forms a tag to be inserted.
     * For example, if creating calendar month html on the fly, one might
     * want to create on-the-fly tags for each day's content.  Using this
     * method guarantees that the tag markers will match the ones in the
     * existing templates.
     * @param tagName
     * @return tagStart + tagName + tagEnd
     */
    public String makeTag(String tagName)
    {
        return tagStart + tagName + tagEnd;
    }

    //
    // boy, this subtemplate code sure is ugly
    // ...but being able to define multiple templates per file sure is handy
    //
    protected StringBuilder readAndCacheTemplate(String name, String extension, BufferedReader brTemp)
        throws IOException
    {
        StringBuilder sbTemp = new StringBuilder();
        String line = null;
        while (brTemp.ready()) {
            line = brTemp.readLine();
            if (line == null) break;
            int comPos = line.indexOf(COMMENT_START);
            int subPos = line.indexOf(SUB_START);
            // first, strip out any comments
            while (comPos > -1 && (subPos < 0 || subPos > comPos)) {
                line = stripComment(comPos,line,brTemp);
                comPos = line.indexOf(COMMENT_START);
                subPos = line.indexOf(SUB_START);
            }
            // then, strip out any subtemplates
            if (subPos > -1) {
                int subEndPos = line.indexOf(SUB_END);
                if (subEndPos == subPos) {
                    // errant subtemplate end marker, ignore
                } else {
                    // parse out new template name and begin recursive separation of subtemplates
                    int subNameEnd = line.indexOf(SUB_NAME_END, subPos + SUB_START.length());
                    if (subNameEnd > -1) {
                        sbTemp.append(line.substring(0,subPos));
                        String subName = line.substring(subPos + SUB_START.length(),subNameEnd);
                        String restOfLine = line.substring(subNameEnd + SUB_NAME_END.length());
                        line = readAndCacheSubTemplate(name + "." + subName, extension, brTemp, restOfLine);
                        // if after removing subtemplate, line is blank, don't output a blank line
                        if (line.length() < 1) continue;
                    }
                }
            }
            sbTemp.append(line);
            if (brTemp.ready()) sbTemp.append("\n");
        }
        addToCache(name,extension,sbTemp);
        return sbTemp;
    }

    private String stripComment(int comPos, String firstLine, BufferedReader brTemp)
        throws IOException
    {
        String beforeComment = firstLine.substring(0,comPos);
        int comEndPos = firstLine.indexOf(COMMENT_END);
        if (comEndPos > -1) {
            // easy case -- comment does not span lines
            comEndPos += COMMENT_END.length();
            return beforeComment + firstLine.substring(comEndPos);
        } else {
            // keep eating lines until the end marker is found
            String line = null;
            while (brTemp.ready()) {
                line = brTemp.readLine();
                if (line == null) break;

                comEndPos = line.indexOf(COMMENT_END);
                if (comEndPos > -1) {
                    comEndPos += COMMENT_END.length();
                    return beforeComment + line.substring(comEndPos);
                }
            }
            // never found!  ate rest of file.  burp.
            return beforeComment;
        }
    }

    private String readAndCacheSubTemplate(String name, String extension,
                                           BufferedReader brTemp, String firstLine)
        throws IOException
    {
        StringBuilder sbTemp = new StringBuilder();
        int subEndPos = firstLine.indexOf(SUB_END);
        int comPos = firstLine.indexOf(COMMENT_START);
        // first, strip out any comments
        while (comPos > -1 && (subEndPos < 0 || subEndPos > comPos)) {
            firstLine = stripComment(comPos,firstLine,brTemp);
            comPos = firstLine.indexOf(COMMENT_START);
            subEndPos = firstLine.indexOf(SUB_END);
        }
        if (subEndPos > -1) {
            sbTemp.append(firstLine.substring(0,subEndPos));
            addToCache(name,extension,sbTemp);
            return firstLine.substring(subEndPos+SUB_END.length());
        } else {
            sbTemp.append(firstLine);
            if (brTemp.ready() && firstLine.length() > 0) sbTemp.append("\n");
            while (brTemp.ready()) {
                String line = brTemp.readLine();
                if (line == null) break;
                comPos = line.indexOf(COMMENT_START);
                int subPos = line.indexOf(SUB_START);
                // strip out comments
                while (comPos > -1 && (subPos < 0 || subPos > comPos)) {
                    line = stripComment(comPos,line,brTemp);
                    comPos = line.indexOf(COMMENT_START);
                    subPos = line.indexOf(SUB_START);
                }
                subEndPos = line.indexOf(SUB_END);
                if (subPos > -1 || subEndPos > -1) {
                    if (subEndPos > -1 && (subPos == -1 || subEndPos <= subPos)) {
                        // wrap it up
                        sbTemp.append(line.substring(0,subEndPos));
                        addToCache(name,extension,sbTemp);
                        return line.substring(subEndPos+SUB_END.length());
                    } else if (subPos > -1) {
                        int subNameEnd = line.indexOf(SUB_NAME_END, subPos + SUB_START.length());
                        if (subNameEnd > -1) {
                            sbTemp.append(line.substring(0,subPos));
                            String subName = line.substring(subPos + SUB_START.length(),subNameEnd);
                            String restOfLine = line.substring(subNameEnd + SUB_NAME_END.length());
                            line = readAndCacheSubTemplate(name + "." + subName, extension, brTemp, restOfLine);
                            // if after removing subtemplate, line is blank, don't output a blank line
                            if (line.length() < 1) continue;
                        }
                    }
                }
                sbTemp.append(line);
                if (brTemp.ready()) sbTemp.append("\n");
            }
            // end of file but with no matching SUB_END? -- wrap it up...
            addToCache(name,extension,sbTemp);
            return "";
        }
    }

    private void addToCache(String name, String extension, StringBuilder template)
    {
        String ref = extension + "." + name;
        template = expandShorthand(name,template);
        cache.put(ref, template);
        cacheFetch.put(ref, new Long(System.currentTimeMillis()) );
    }

    private StringBuilder expandShorthand(String name, StringBuilder template)
    {
        // to allow shorthand intra-template references, must pre-process the template
        // at this point and expand any intra-template references, eg:
        //  {~.includeIf(...).#xxx} => {~.includeIf(...).template_name#xxx}
        //
        // Hmm, refs that start with a hash should always be toplevel!
        //  so {#subtemplate}...{~.includeIf(...).#xxx} ...{#}
        //  is a reference to template_name#xxx NOT a nested sub like template_name#subtemplate#xxx
        //
        // might not be worth it, would have to track down refs inside onmatch and ondefined filters
        // although it could be more efficient to expand shorthand syntax at this stage:
        //  {+#sub} => {~.include.template_name#sub}
        //  {+(cond)#sub} => {~.includeIf(cond).template_name#sub}
        //
        // and you'd have to catch stuff like this...
        // {~asdf|onmatch(/xyz/,+#xyz,/abc/,+#abc)nomatch(+#def)}
        //  => {~asdf|onmatch(/xyz/,~.include.template_name#xyz,/abc/,~.include.template_name#abc)nomatch(~.include.template_name#def)}
        //
        // or even just default values a la (I don't even remember, is this supported?)
        //  {~asdf:+#def} => {~asdf:+template_name#def} => {~asdf:~.include.template_name#def}

        // determine what shorthand refs should expand into
        // (template filename is everything up to the first dot)
        String fullRef = name;
        int dotPos = fullRef.indexOf('.');
        if (dotPos > 0) fullRef = name.substring(0,dotPos);

        // restrict search to inside tags
        int cursor = template.indexOf("{");

        while (cursor > -1) {
            if (template.length() == cursor+1) return template; // kick out at first sign of trouble
            char afterBrace = template.charAt(cursor+1);
            if (afterBrace == '+') {
                cursor = expandShorthandInclude(template,fullRef,cursor);
            } else if (afterBrace == '~') {
                cursor = expandShorthandTag(template,fullRef,cursor);
            } else if (afterBrace == '^') {
                // ^ is shorthand for ~. eg {^include.#xyz} or {^wiki.External_Content}
                template.replace(cursor+1,cursor+2,"~.");
                // re-process, do not advance cursor.
            } else if (afterBrace == '*') {
                cursor = expandShorthandMacro(template,fullRef,cursor);
            } else {
                cursor += 2;
            }
            // on to the next tag...
            if (cursor > -1) cursor = template.indexOf("{",cursor);
        }
        return template;
    }

    private int expandFnArgs(StringBuilder template, String fullRef, int cursor, String fnCall, int tagEnd)
    {
        int tagCursor = 0;
        StringBuilder expanded = null;

        int hashPos = fnCall.indexOf("#");

        // let's just (lame but works) assume that all hashes after a
        // delimiter are hashrefs that need to be expanded
        while (hashPos > 1) {
            char preH = fnCall.charAt(hashPos-1);
            if (preH == '"' || preH == ',' || preH == ' ' || preH == '(') {
                if (expanded == null) expanded = new StringBuilder();
                // everything new up to now is certified "clean"
                expanded.append(fnCall.substring(tagCursor,hashPos));
                // pop in the base template ref
                expanded.append(fullRef);
                tagCursor = hashPos;
            }

            hashPos = fnCall.indexOf("#",hashPos+1);

        }

        if (expanded != null) {
            // grab the tail
            expanded.append(fnCall.substring(tagCursor));
            String expandedTag = expanded.toString();
            // insert tag, now with fully-qualified refs back into template
            template.replace(cursor+2,tagEnd,expandedTag);
            // update tagEnd to reflect added chars
            tagEnd += (expandedTag.length() - fnCall.length());
        }
        cursor = tagEnd+1;

        return cursor;
    }

    private int expandShorthandMacro(StringBuilder template, String fullRef, int cursor)
    {
    	int offset = 2;
    	while (template.charAt(cursor+offset) == ' ') offset++;
    	
        if (template.charAt(cursor+offset) == '#') {
            template.insert(cursor+offset,fullRef);
            int macroMarkerEnd = template.indexOf(MACRO_NAME_END,cursor+offset+fullRef.length()+1);
            if (macroMarkerEnd < 0) return cursor+1;
            return macroMarkerEnd + MACRO_NAME_END.length();
        }
        int macroMarkerEnd = template.indexOf(MACRO_NAME_END,cursor+offset);
        if (macroMarkerEnd < 0) return cursor+1;
        return macroMarkerEnd + MACRO_NAME_END.length();
    }

    private int expandShorthandTag(StringBuilder template, String fullRef, int cursor)
    {
        int tagEnd = nextUnescapedDelim("}",template,cursor+2);
        if (tagEnd < 0) return -1; // kick out at any sign of trouble

        // so, this is lame but 99.999% of the time the following strings
        // inside a tag body can be expanded correctly without regard to context:
        //
        //  ,+# => ,~.include.xxx# - inside onmatch
        //  :+# => :~.include.xxx# - ifnull-include
        //  (+# => (~.include.xxx# - inside nomatch/ondefined
        //  ).# => ).xxx# => - long includeIf(...) syntax
        //  ~.include.# => ~.include.xxx# - long include syntax
        //
        // where xxx is the fully qualified template reference
        //
        // not the most efficient, but fast enough
        //
        String tagDirective = template.substring(cursor+2,tagEnd);

        //
        // shorthand refs in fnCall args like ^loop(...) and ^grid(...)
        // will present a little differently.
        //
        if (tagDirective.startsWith(".loop") || tagDirective.startsWith(".grid")) {
            return expandFnArgs(template, fullRef, cursor, tagDirective, tagEnd);
        }

        int tagCursor = 0;
        StringBuilder expanded = null;

        int hashPos = tagDirective.indexOf("#");

        while (hashPos > 1) {
            char a = tagDirective.charAt(hashPos-2);
            char b = tagDirective.charAt(hashPos-1);
            if (b == '+') {
                if (a == ',' || a == ':' || a == '(') {
                    if (expanded == null) expanded = new StringBuilder();
                    expanded.append(tagDirective.substring(tagCursor,hashPos-1));
                    expanded.append("~.include.");
                    expanded.append(fullRef);
                    tagCursor = hashPos;
                }
            } else if ((a == ')' || a == 'e') && b == '.') {
                if (expanded == null) expanded = new StringBuilder();
                expanded.append(tagDirective.substring(tagCursor,hashPos));
                expanded.append(fullRef);
                tagCursor = hashPos;
            }

            hashPos = tagDirective.indexOf("#",hashPos+1);
        }
        if (expanded != null) {
            expanded.append(tagDirective.substring(tagCursor));
            String expandedTag = expanded.toString();
            template.replace(cursor+2,tagEnd,expandedTag);
            // update tagEnd to reflect added chars
            tagEnd += (expandedTag.length() - tagDirective.length());
        }
        cursor = tagEnd+1;

        return cursor;
    }

    private int expandShorthandInclude(StringBuilder template, String fullRef, int cursor)
    {
        if (template.length() == cursor+2) return -1;
        char afterPlus = template.charAt(cursor+2);
        if (afterPlus == '#') {
            // got one, replace + with long include syntax and fully qualified reference
            template.replace(cursor+1,cursor+2,"~.include."+fullRef);
            cursor += 11; // skip {~.include.
            cursor += fullRef.length(); // skip what we just inserted
            cursor = template.indexOf("}",cursor);
        } else if (afterPlus == '(') {
            // scan to end of condition
            int endCond = nextUnescapedDelim(")",template,cursor+3);
            if (endCond < 0) return -1; // kick out at any sign of trouble
            String cond = template.substring(cursor+2,endCond+1);
            if (template.length() == endCond+1) return -1;
            if (template.charAt(endCond) == '#') {
                // got one, replace +(cond) with long includeIf syntax and FQRef
                String expanded = "~.includeIf"+cond+"."+fullRef;
                template.replace(cursor+1,endCond+1,expanded);
                cursor++; // skip {
                cursor += expanded.length();
                cursor = template.indexOf("}",cursor);
            }
        } else {
            // move along, nothing to expand here.
            cursor += 2;
        }

        return cursor;
    }

    public static int nextUnescapedDelim(String delim, StringBuilder sb, int searchFrom)
    {
        int delimPos = sb.indexOf(delim, searchFrom);

        boolean isProvenDelimeter = false;
        while (!isProvenDelimeter) {
            // count number of backslashes that precede this forward slash
            int bsCount = 0;
            while (delimPos-(1+bsCount) >= searchFrom && sb.charAt(delimPos - (1+bsCount)) == '\\') {
                bsCount++;
            }
            // if odd number of backslashes precede this delimiter char, it's escaped
            // if even number precede, it's not escaped, it's the true delimiter
            // (because it's preceded by either no backslash or an escaped backslash)
            if (bsCount % 2 == 0) {
                isProvenDelimeter = true;
            } else {
                // keep looking for real delimiter
                delimPos = sb.indexOf(delim, delimPos+1);
                // if the expr is not legal (missing delimiters??), bail out
                if (delimPos < 0) return -1;
            }
        }
        return delimPos;
    }

    protected StringBuilder getFromCache(String name, String extension)
    {
        String ref = extension + "." + name.replace('#','.');
        StringBuilder template = null;

        long cacheHowLong = dirtyInterval * oneMinuteInMillis;
        if (cacheHowLong < MIN_CACHE) cacheHowLong = MIN_CACHE;

        if (cache.containsKey(ref)) {
            long lastFetch = ((Long) cacheFetch.get(ref)).longValue(); // millis
            long expireTime = lastFetch + cacheHowLong;
            if (System.currentTimeMillis() < expireTime) {
                template = (StringBuilder) cache.get(ref);
            }
        }
        return template;
    }

    /**
     * Forces subsequent template fetching to re-read the template contents
     * from the filesystem instead of the cache.
     */
    public void clearCache()
    {
        cache.clear();
        cacheFetch.clear();
    }

    /**
     * Controls caching behavior.  Set to zero to minimize caching.
     * @param minutes how long to keep a template in the cache.
     */
    public void setDirtyInterval(int minutes)
    {
        dirtyInterval = minutes;
    }

    /**
     * Converts a template with an alternate tag syntax to one that matches
     * this TemplateSet's tags.
     * @param withOldTags Template text which contains tags with the old syntax
     * @param oldTagStart old tag beginning marker
     * @param oldTagEnd old tag end marker
     * @return template with tags converted
     */
    public String convertToMyTags(String withOldTags, String oldTagStart, String oldTagEnd)
    {
        return convertTags(withOldTags, oldTagStart, oldTagEnd, this.tagStart, this.tagEnd);
    }

    /**
     * Converts a template with an alternate tag syntax to one that matches
     * the default tag syntax {~myTag}.
     * @param withOldTags Template text which contains tags with the old syntax
     * @param oldTagStart old tag beginning marker
     * @param oldTagEnd old tag end marker
     * @return template with tags converted
     */
    public static String convertTags(String withOldTags, String oldTagStart, String oldTagEnd)
    {
        return convertTags(withOldTags, oldTagStart, oldTagEnd,
                           DEFAULT_TAG_START, DEFAULT_TAG_END);
    }

    /**
     * Converts a template from one tag syntax to another.
     * @param withOldTags Template text which contains tags with the old syntax
     * @param oldTagStart old tag beginning marker
     * @param oldTagEnd old tag end marker
     * @param newTagStart new tag beginning marker
     * @param newTagEnd new tag end marker
     * @return template with tags converted
     */
    public static String convertTags(String withOldTags, String oldTagStart, String oldTagEnd,
                                     String newTagStart, String newTagEnd)
    {
        StringBuilder converted = new StringBuilder();
        int j, k, marker = 0;
        while ((j = withOldTags.indexOf(oldTagStart,marker)) > -1) {
            converted.append(withOldTags.substring(marker,j));
            marker = j + oldTagStart.length();
            if ((k = withOldTags.indexOf(oldTagEnd)) > -1) {
                converted.append(newTagStart);
                converted.append(withOldTags.substring(marker,k));
                converted.append(newTagEnd);
                marker = k + oldTagEnd.length();
            } else {
                converted.append(oldTagStart);
            }
        }
        if (marker == 0) {
            return withOldTags;
        } else {
            converted.append(withOldTags.substring(marker));
            return converted.toString();
        }
    }

    public TemplateSet getSubset(String context)
    {
        return new TemplateSetSlice(this, context);
    }

    // chunk factory now supports sharing content sources with its factory-created chunks
    private HashSet<ContentSource> altSources = null;

    public void addProtocol(ContentSource src)
    {
        if (altSources == null) altSources = new HashSet<ContentSource>();
        altSources.add(src);
    }

    private void shareContentSources(Chunk c)
    {
        if (altSources == null) return;
        java.util.Iterator<ContentSource> iter = altSources.iterator();
        while (iter.hasNext()) {
            ContentSource src = iter.next();
            c.addProtocol(src);
        }
    }
    
    public void signalFailureWithNull()
    {
    	this.prettyFail = false;	
    }
    
    private String truncateNameToStub(String name)
    {
    	int slashPos = name.lastIndexOf('/');
    	if (slashPos < -1) slashPos = name.lastIndexOf('\\');
    	
    	String folder = null;
    	String stub;
    	if (slashPos > -1) {
    		folder = name.substring(0,slashPos+1);
    		stub = name.substring(slashPos+1).replace('#','.');
    	} else {
    		stub = name.replace('#','.');
    	}
    	
        int dotPos = stub.indexOf(".");
        if (dotPos > -1) stub = stub.substring(0,dotPos);
        
        if (slashPos > -1) {
            char fs = System.getProperty("file.separator").charAt(0);
            folder.replace('\\',fs);
            folder.replace('/',fs);
	        return folder + stub;
        } else {
        	return stub;
        }
    }
    
	public String getTemplatePath(String templateName, String ext)
	{
		String stub = truncateNameToStub(templateName);
		return templatePath + stub + '.' + ext;
	}
	
    public String getDefaultExtension()
    {
    	return this.defaultExtension;
    }

    public boolean provides(String itemName)
    {
    	String found = _get(itemName, defaultExtension, false);
    	if (found == null) {
    		return false;
    	} else {
    		return true;
    	}
    }
    
}
