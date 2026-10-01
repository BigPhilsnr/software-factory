package dev.softwarefactory.generation.tools;

import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** Reduces a fetched HTML page to the text a reader would see. */
final class HtmlText {
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" ?\\n ?");
    private static final String HIDDEN_ELEMENTS = "script, style, noscript, template, svg";

    private HtmlText() {}

    /** Visible text only: scripts, styles and other non-rendered content are dropped, never executed. */
    static String visible(String html) {
        Document document = Jsoup.parse(html);
        document.select(HIDDEN_ELEMENTS).remove();
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(
                new NodeVisitor() {
                    @Override
                    public void head(Node node, int depth) {
                        if (node instanceof TextNode textNode)
                            text.append(textNode.text()).append(' ');
                    }

                    @Override
                    public void tail(Node node, int depth) {
                        if (node instanceof Element element && element.isBlock()) text.append('\n');
                    }
                },
                document.body());
        String collapsed = SPACE_AROUND_NEWLINE
                .matcher(HORIZONTAL_SPACE.matcher(text).replaceAll(" "))
                .replaceAll("\n");
        return BLANK_LINES.matcher(collapsed).replaceAll("\n").strip();
    }
}
