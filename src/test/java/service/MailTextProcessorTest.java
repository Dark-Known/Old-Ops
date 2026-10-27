package service;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Pins the behaviour of the mail text handling that used to live inside TransferService. */
public class MailTextProcessorTest {

    private static MailTextProcessor proc(File dir) { return new MailTextProcessor(() -> dir); }

    private static File tmp() throws Exception {
        File d = Files.createTempDirectory("opstool-mail").toFile();
        d.deleteOnExit();
        return d;
    }

    @Test public void messageIdsCountUpAndWrapAfter99999() throws Exception {
        File d = tmp();
        MailTextProcessor p = proc(d);
        assertEquals("00001", p.nextMessageId());
        assertEquals("00002", p.nextMessageId());
        Files.writeString(new File(d, "msgid-counter.txt").toPath(), "99999", StandardCharsets.UTF_8);
        assertEquals("00001", p.nextMessageId());
    }

    @Test public void messageIdCounterIsSharedBetweenInstances() throws Exception {
        File d = tmp();
        assertEquals("00001", proc(d).nextMessageId());
        assertEquals("00002", proc(d).nextMessageId());
    }

    @Test public void messageIdsAreUniqueUnderParallelUse() throws Exception {
        File d = tmp();
        MailTextProcessor p = proc(d);
        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> { for (int k = 0; k < 25; k++) seen.add(p.nextMessageId()); });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(200, seen.size());
    }

    @Test public void blankLinesAreRemovedAndNullIsEmpty() throws Exception {
        MailTextProcessor p = proc(tmp());
        assertEquals("", p.removeBlankLines(null));
        String out = p.removeBlankLines("A\n\n  \nB\n");
        assertFalse(out, out.contains("\n\n"));
        assertTrue(out.contains("A") && out.contains("B"));
    }

    @Test public void plainTextIsLeftAloneAndHtmlIsStripped() throws Exception {
        MailTextProcessor p = proc(tmp());
        assertEquals("a <b> c", p.toPlainText("a <b> c", "text"));
        String t = p.toPlainText("<html><body><p>Hello</p><p>World</p></body></html>", "html");
        assertTrue(t, t.contains("Hello") && t.contains("World") && !t.contains("<p>"));
        assertEquals("", p.toPlainText(null, "html"));
    }

    @Test public void messageTypeMarkerIsFoundAtItsPosition() throws Exception {
        MailTextProcessor p = proc(tmp());
        MailTextProcessor.MarkerMatch m = p.findMessageTypeMarker("blah\nLDM\n1234");
        assertNotNull(m);
        assertEquals("LDM", m.type);
        assertEquals(5, m.index);
        assertNull(p.findMessageTypeMarker("nothing here"));
        assertNull(p.findMessageTypeMarker(null));
    }

    @Test public void quotedRepliesAndSignaturesAreCutOff() throws Exception {
        MailTextProcessor p = proc(tmp());
        String out = p.stripSignatureAndQuotedContent("LDM\nbody line\n\nOn Mon, 5 Oct 2026, Bob wrote:\n> old text");
        assertTrue(out, out.contains("body line"));
        assertFalse(out, out.contains("old text"));
        assertEquals("", p.stripSignatureAndQuotedContent(null));
    }
}
