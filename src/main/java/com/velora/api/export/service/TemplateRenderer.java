package com.velora.api.export.service;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders a Thymeleaf template to an HTML string, for the PDF renderer to consume.
 *
 * <p>Thin on purpose: it exists so the PDF pipeline is "template to string, string to
 * PDF" and each half can be tested on its own. Debugging a broken layout is far
 * easier when the intermediate HTML can be dumped to a file and opened in a browser.
 */
@Service
public class TemplateRenderer {

    /**
     * Arabic, with Latin digits. Plain {@code ar} makes {@code #numbers.formatDecimal} print
     * Arabic-Indic digits (٨٥٠.٠٠), which is not how commercial documents are written in Egypt
     * and cannot be typed into an accounting system as it stands. The {@code nu-latn} extension
     * changes the digits only; the language, direction and layout are the template's own.
     */
    private static final java.util.Locale DOCUMENT_LOCALE =
            java.util.Locale.forLanguageTag("ar-u-nu-latn");

    private final TemplateEngine templateEngine;

    public TemplateRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** Several variables at once, for templates that need more than one model object. */
    public String render(String templateName, java.util.Map<String, Object> variables) {
        Context context = new Context(DOCUMENT_LOCALE);
        context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }

    public String render(String templateName, String variableName, Object model) {
        Context context = new Context(DOCUMENT_LOCALE);
        context.setVariable(variableName, model);
        return templateEngine.process(templateName, context);
    }
}
