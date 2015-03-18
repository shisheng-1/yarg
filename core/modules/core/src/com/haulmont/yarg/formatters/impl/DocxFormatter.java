/*
 * Copyright 2013 Haulmont
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.haulmont.yarg.formatters.impl;


import com.haulmont.yarg.formatters.factory.FormatterFactoryInput;
import com.haulmont.yarg.formatters.impl.inline.ContentInliner;
import com.haulmont.yarg.formatters.impl.xls.PdfConverter;
import com.haulmont.yarg.structure.BandData;
import com.haulmont.yarg.structure.ReportFieldFormat;
import com.haulmont.yarg.structure.ReportOutputType;
import org.apache.commons.io.IOUtils;
import org.docx4j.Docx4J;
import org.docx4j.TextUtils;
import org.docx4j.TraversalUtil;
import org.docx4j.XmlUtils;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.model.structure.HeaderFooterPolicy;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.io.SaveToZipFile;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.*;
import org.jvnet.jaxb2_commons.ppp.Child;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang.StringUtils.*;

/**
 * * Document formatter for '.docx' file types
 */
public class DocxFormatter extends AbstractFormatter {
    protected WordprocessingMLPackage wordprocessingMLPackage;
    protected DocumentWrapper documentWrapper;
    protected PdfConverter pdfConverter;

    public DocxFormatter(FormatterFactoryInput formatterFactoryInput) {
        super(formatterFactoryInput);
        supportedOutputTypes.add(ReportOutputType.docx);
        supportedOutputTypes.add(ReportOutputType.pdf);
    }

    public void setPdfConverter(PdfConverter pdfConverter) {
        this.pdfConverter = pdfConverter;
    }

    @Override
    public void renderDocument() {
        loadDocument();

        fillTables();

        replaceAllAliasesInDocument();

        saveAndClose();
    }

    protected void loadDocument() {
        if (reportTemplate == null)
            throw new NullPointerException("Template file can't be null.");
        try {
            wordprocessingMLPackage = WordprocessingMLPackage.load(reportTemplate.getDocumentContent());
            documentWrapper = new DocumentWrapper(wordprocessingMLPackage.getMainDocumentPart());
        } catch (Docx4JException e) {
            throw wrapWithReportingException(String.format("An error occurred while reading docx template. File name [%s]", reportTemplate.getDocumentName()), e);
        }
    }

    protected void saveAndClose() {
        try {
            if (ReportOutputType.docx.equals(reportTemplate.getOutputType())) {
                writeToOutputStream(wordprocessingMLPackage, outputStream);
                outputStream.flush();
            } else if (ReportOutputType.pdf.equals(reportTemplate.getOutputType())) {
                if (pdfConverter != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    writeToOutputStream(wordprocessingMLPackage, bos);
                    pdfConverter.convertToPdf(PdfConverter.FileType.DOCUMENT, bos.toByteArray(), outputStream);
                    outputStream.flush();
                } else {
                    Docx4J.toPDF(wordprocessingMLPackage, outputStream);
                    outputStream.flush();
                }
            } else if (ReportOutputType.html.equals(reportTemplate.getOutputType())) {
                HTMLSettings htmlSettings = Docx4J.createHTMLSettings();
                htmlSettings.setWmlPackage(wordprocessingMLPackage);
                Docx4J.toHTML(htmlSettings, outputStream, Docx4J.FLAG_NONE);
                outputStream.flush();
            } else {
                throw new UnsupportedOperationException(String.format("DocxFormatter could not output file with type [%s]", reportTemplate.getOutputType()));
            }
        } catch (Docx4JException e) {
            throw wrapWithReportingException("An error occurred while saving result report", e);
        } catch (IOException e) {
            throw wrapWithReportingException("An error occurred while saving result report to PDF", e);
        } finally {
            IOUtils.closeQuietly(outputStream);
        }
    }

    protected void replaceAllAliasesInDocument() {
        for (TextWrapper text : documentWrapper.texts) {
            text.fillTextWithBandData();
        }
    }

    protected void fillTables() {
        for (TableManager resultingTable : documentWrapper.tables) {
            if (resultingTable.rowWithAliases != null) {
                List<BandData> bands = rootBand.findBandsRecursively(resultingTable.bandName);
                for (final BandData band : bands) {
                    Tr newRow = resultingTable.copyRow(resultingTable.rowWithAliases);
                    resultingTable.fillRowFromBand(newRow, band);
                }
                resultingTable.table.getContent().remove(resultingTable.rowWithAliases);
            }
        }
    }

    protected String getElementText(Object element) {
        StringWriter w = new StringWriter();
        try {
            TextUtils.extractText(element, w);
        } catch (Exception e) {
            throw wrapWithReportingException(
                    String.format("An error occurred while rendering docx template. File name [%s]",
                            reportTemplate.getDocumentName()), e);
        }

        return w.toString();
    }

    protected class DocumentWrapper {
        protected MainDocumentPart mainDocumentPart;
        protected Set<TableManager> tables;
        protected Set<TextWrapper> texts;

        protected DocumentWrapper(MainDocumentPart mainDocumentPart) {
            this.mainDocumentPart = mainDocumentPart;
            collectData();
        }

        protected void collectDataFromObjects(Object... objects) {
            for (Object object : objects) {
                if (object != null) {
                    TextVisitor collectAliasesCallback = new TextVisitor();
                    new TraversalUtil(object, collectAliasesCallback);
                    texts.addAll(collectAliasesCallback.textWrappers);
                }
            }
        }

        void collectData() {
            TableCollector collectTablesCallback = new TableCollector();
            new TraversalUtil(mainDocumentPart, collectTablesCallback);
            TextVisitor collectAliasesCallback = new TextVisitor();
            new TraversalUtil(mainDocumentPart, collectAliasesCallback);
            tables = collectTablesCallback.tableManagers;
            texts = collectAliasesCallback.textWrappers;

            //collect data from headers
            List<SectionWrapper> sectionWrappers = wordprocessingMLPackage.getDocumentModel().getSections();
            for (SectionWrapper sw : sectionWrappers) {
                HeaderFooterPolicy hfp = sw.getHeaderFooterPolicy();
                collectDataFromObjects(hfp.getFirstHeader(), hfp.getDefaultHeader(), hfp.getEvenHeader(), hfp.getFirstFooter(), hfp.getDefaultFooter(), hfp.getEvenFooter());
            }
        }
    }

    protected class TextWrapper {
        protected Text text;

        protected TextWrapper(Text text) {
            this.text = text;
        }

        void fillTextWithBandData() {

            Matcher matcher = ALIAS_WITH_BAND_NAME_PATTERN.matcher(text.getValue());
            while (matcher.find()) {
                String alias = matcher.group(1);
                String stringFunction = matcher.group(2);

                BandPathAndParameterName bandAndParameter = separateBandNameAndParameterName(alias);

                if (isBlank(bandAndParameter.bandPath) || isBlank(bandAndParameter.parameterName)) {
                    if (alias.matches("[A-z0-9_\\.]+?")) {//skip aliases in tables
                        continue;
                    }

                    throw wrapWithReportingException("Bad alias : " + text.getValue());
                }

                BandData band = findBandByPath(rootBand, bandAndParameter.bandPath);

                if (band == null) {
                    throw wrapWithReportingException(String.format("No band for alias [%s] found", alias));
                }

                String fullParameterName = band.getName() + "." + bandAndParameter.parameterName;
                Object paramValue = band.getParameterValue(bandAndParameter.parameterName);

                Map<String, ReportFieldFormat> valueFormats = rootBand.getReportFieldFormats();
                if (paramValue != null && valueFormats != null && valueFormats.containsKey(fullParameterName)) {
                    String format = valueFormats.get(fullParameterName).getFormat();
                    for (ContentInliner contentInliner : DocxFormatter.this.contentInliners) {
                        Matcher contentMatcher = contentInliner.getTagPattern().matcher(format);
                        if (contentMatcher.find()) {
                            contentInliner.inlineToDocx(wordprocessingMLPackage, text, paramValue, contentMatcher);
                            return;
                        }
                    }
                }

                text.setValue(inlineParameterValue(text.getValue(), alias, formatValue(paramValue, bandAndParameter.parameterName, fullParameterName, stringFunction)));
                text.setSpace("preserve");
            }
        }
    }

    protected class TableManager {
        protected Tbl table;
        protected Tr firstRow = null;
        protected Tr rowWithAliases = null;
        protected String bandName = null;

        TableManager(Tbl tbl) {
            this.table = tbl;
        }

        public Tr copyRow(Tr row) {
            Tr copiedRow = XmlUtils.deepCopy(row);
            int index = table.getContent().indexOf(row);
            table.getContent().add(index, copiedRow);
            return copiedRow;
        }

        public void fillRowFromBand(Tr row, final BandData band) {
            new TraversalUtil(row, new AliasVisitor() {
                @Override
                protected void handle(Text text) {
                    String sourceString = text.getValue();
                    String resultString = insertBandDataToString(band, sourceString);
                    text.setValue(resultString);
                    text.setSpace("preserve");
                }
            });
        }
    }

    protected class TextVisitor extends AliasVisitor {
        protected Set<TextWrapper> textWrappers = new HashSet<TextWrapper>();

        @Override
        protected void handle(Text text) {
            textWrappers.add(new TextWrapper(text));
        }
    }

    protected abstract class AliasVisitor extends TraversalUtil.CallbackImpl {
        @Override
        public List<Object> apply(Object o) {
            if (o instanceof P) {
                String paragraphText = getElementText(o);

                if (UNIVERSAL_ALIAS_PATTERN.matcher(paragraphText).find()) {
                    Set<Text> mergedTexts = new TextMerger((P) o, UNIVERSAL_ALIAS_REGEXP).mergeMatchedTexts();
                    for (Text text : mergedTexts) {
                        handle(text);
                    }
                }
            }

            return null;
        }

        protected abstract void handle(Text text);

        public void walkJAXBElements(Object parent) {
            List children = getChildren(parent);
            if (children != null) {

                for (Object object : children) {
                    object = XmlUtils.unwrap(object);

                    if (object instanceof Child) {
                        ((Child) object).setParent(parent);
                    }

                    this.apply(object);

                    if (this.shouldTraverse(object)) {
                        walkJAXBElements(object);
                    }
                }
            }
        }
    }

    protected class TableCollector extends TraversalUtil.CallbackImpl {
        protected Stack<TableManager> currentTables = new Stack<TableManager>();
        protected Set<TableManager> tableManagers = new HashSet<TableManager>();
        protected boolean skipCurrentTable = false;

        public List<Object> apply(Object object) {
            if (skipCurrentTable) return null;

            if (object instanceof Tr) {
                Tr currentRow = (Tr) object;
                final TableManager currentTable = currentTables.peek();

                if (currentTable.firstRow == null) {
                    currentTable.firstRow = currentRow;

                    findNameForCurrentTable(currentTable);

                    if (currentTable.bandName == null) {
                        skipCurrentTable = true;
                    } else {
                        tableManagers.add(currentTable);
                    }
                }

                if (currentTable.rowWithAliases == null) {
                    RegexpFinder aliasFinder = new RegexpFinder<P>(UNIVERSAL_ALIAS_PATTERN, P.class);
                    new TraversalUtil(currentRow, aliasFinder);

                    if (aliasFinder.getValue() != null) {
                        currentTable.rowWithAliases = currentRow;
                    }
                }
            }

            return null;
        }

        protected void findNameForCurrentTable(final TableManager currentTable) {
            new TraversalUtil(currentTable.firstRow,
                    new RegexpFinder<P>(BAND_NAME_DECLARATION_PATTERN, P.class) {
                        @Override
                        protected void onFind(P paragraph, Matcher matcher) {
                            super.onFind(paragraph, matcher);
                            currentTable.bandName = matcher.group(1);
                            String bandNameDeclaration = matcher.group();
                            Set<Text> mergedTexts = new TextMerger(paragraph, bandNameDeclaration).mergeMatchedTexts();
                            for (Text text : mergedTexts) {
                                text.setValue(text.getValue().replace(bandNameDeclaration, ""));
                            }
                        }
                    });
        }

        // Depth first
        public void walkJAXBElements(Object parent) {
            List children = getChildren(parent);
            if (children != null) {

                for (Object o : children) {
                    o = XmlUtils.unwrap(o);

                    if (o instanceof Child) {
                        ((Child) o).setParent(parent);
                    }

                    if (o instanceof Tbl) {
                        currentTables.push(new TableManager((Tbl) o));
                    }

                    this.apply(o);

                    if (this.shouldTraverse(o)) {
                        walkJAXBElements(o);
                    }

                    if (o instanceof Tbl) {
                        currentTables.pop();
                        skipCurrentTable = false;
                    }

                }
            }
        }
    }

    protected class RegexpFinder<T> extends TraversalUtil.CallbackImpl {
        protected Class<T> classToHandle;
        protected Pattern regularExpression;
        protected String value;

        public RegexpFinder(Pattern regularExpression, Class<T> classToHandle) {
            this.regularExpression = regularExpression;
            this.classToHandle = classToHandle;
        }

        @Override
        public List<Object> apply(Object o) {
            if (classToHandle.isAssignableFrom(o.getClass())) {
                @SuppressWarnings("unchecked")
                T currentElement = (T) o;
                String currentElementText = getElementText(currentElement);
                if (isNotBlank(currentElementText)) {
                    Matcher matcher = regularExpression.matcher(currentElementText);
                    if (matcher.find()) {
                        onFind(currentElement, matcher);
                    }
                }
            }

            return null;
        }

        protected void onFind(T o, Matcher matcher) {
            value = matcher.group(0);
        }

        public String getValue() {
            return value;
        }
    }

    protected class TextMerger {
        protected Set<Text> resultingTexts = new HashSet<Text>();
        protected Set<Text> textsToRemove = new HashSet<Text>();

        protected Text startText = null;
        protected Set<Text> mergedTexts = null;
        protected StringBuilder mergedTextsString = null;
        protected Pattern regexpPattern;
        protected P paragraph;
        protected String regexp;
        protected String first2SymbolsOfRegexp;

        public TextMerger(P paragraph, String regexp) {
            this.paragraph = paragraph;
            this.regexp = regexp;
            this.regexpPattern = Pattern.compile(regexp);
            this.first2SymbolsOfRegexp = regexp.replaceAll("\\\\", "").substring(0, 2);
        }

        public Set<Text> mergeMatchedTexts() {
            for (Object paragraphContentObject : paragraph.getContent()) {
                if (paragraphContentObject instanceof R) {
                    R currentRun = (R) paragraphContentObject;
                    for (Object runContentObject : currentRun.getContent()) {
                        Object unwrappedRunContenObject = XmlUtils.unwrap(runContentObject);
                        if (unwrappedRunContenObject instanceof Text) {
                            handleText((Text) unwrappedRunContenObject);
                        }
                    }
                }
            }

            removeUnnecessaryTexts();

            return resultingTexts;
        }

        protected void removeUnnecessaryTexts() {
            for (Text text : textsToRemove) {
                Object parent = XmlUtils.unwrap(text.getParent());
                if (parent instanceof R) {
                    ((R) parent).getContent().remove(text);
                }
            }
        }

        protected void handleText(Text currentText) {
            if (startText == null && containsStartOfRegexp(currentText.getValue())) {
                initMergeQueue(currentText);
            }

            if (startText != null) {
                addToMergeQueue(currentText);

                if (mergeQueueMatchesRegexp()) {
                    handleMatchedText();
                }
            }
        }

        private void initMergeQueue(Text currentText) {
            startText = currentText;
            mergedTexts = new HashSet<Text>();
            mergedTextsString = new StringBuilder();
        }

        private boolean containsStartOfRegexp(String text) {
            return text.contains(first2SymbolsOfRegexp);
        }

        protected void addToMergeQueue(Text currentText) {
            mergedTexts.add(currentText);
            mergedTextsString.append(currentText.getValue());
        }

        protected boolean mergeQueueMatchesRegexp() {
            return regexpPattern.matcher(mergedTextsString).find();
        }

        protected void handleMatchedText() {
            resultingTexts.add(startText);
            startText.setValue(mergedTextsString.toString());
            for (Text mergedText : mergedTexts) {
                if (mergedText != startText) {
                    mergedText.setValue("");
                    textsToRemove.add(mergedText);
                }
            }

            if (!containsStartOfRegexp(startText.getValue().replace(regexp, ""))) {
                startText = null;
                mergedTexts = null;
                mergedTextsString = null;
            } else {
                mergedTexts = new HashSet<Text>();
                mergedTexts.add(startText);
                mergedTextsString = new StringBuilder(startText.getValue());
            }
        }
    }


    protected void writeToOutputStream(WordprocessingMLPackage mlPackage, OutputStream outputStream) throws Docx4JException {
        SaveToZipFile saver = new SaveToZipFile(mlPackage);
        saver.save(outputStream);
    }
}