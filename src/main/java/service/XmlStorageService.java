package service;

import model.Credential;
import model.ScheduledTask;
import util.*;
import util.MailFetchMode;
import org.w3c.dom.*;
import model.ScheduledTask.*;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists tasks and credentials as XML.
 *
 * Credential storage layout (per-user files):
 *   <dataDir>/creds_<username>.xml
 *
 * Each file holds ONE credential entry for that username.
 * The password is stored as plain text. When the ops team enters a username
 * in the Task Dialog, the matching creds_<username>.xml is loaded to supply
 * the password automatically.
 *
 * Task storage:
 *   <dataDir>/tasks.xml
 */
public class XmlStorageService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final File dataDir;
    private final File taskFile;
    private final Object taskFileLock = new Object();

    public XmlStorageService(String dataDirPath) {
        this.dataDir  = new File(dataDirPath);
        this.dataDir.mkdirs();
        this.taskFile = new File(dataDir, "tasks.xml");
    }

    public File getDataDir() {
        return dataDir;
    }

    // ─── Per-user credential file helpers ───────────────────────────────────

    /** Returns the creds_<username>.xml file for the given username. */
    public File credFileForUser(String username) {
        // Sanitise username so it is safe as a filename
        String safe = username.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return new File(dataDir, "creds_" + safe + ".xml");
    }

    /**
     * Look up credentials by username.
     * Reads creds_<username>.xml and returns the Credential, or null if not found.
     */
    public Credential loadCredentialByUsername(String username) {
        if (username == null || username.isEmpty()) return null;
        File f = credFileForUser(username);
        if (!f.exists()) return null;
        try {
            Document doc = parseXml(f);
            NodeList nodes = doc.getElementsByTagName("credential");
            if (nodes.getLength() == 0) return null;
            return elementToCredential((Element) nodes.item(0));
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Save a credential into its per-user XML file (creds_<username>.xml).
     * One file = one user; calling save replaces the existing entry.
     */
    public void saveCredential(Credential cred) {
        if (cred.getId() == null || cred.getId().isEmpty()) {
            cred.setId(UUID.randomUUID().toString());
        }
        try {
            Document doc = newDoc("credentials");
            Element root = doc.getDocumentElement();
            root.appendChild(credentialToElement(doc, cred));
            writeXml(doc, credFileForUser(cred.getUsername()));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Delete the per-user creds file for the given username. */
    public void deleteCredential(String username) {
        File f = credFileForUser(username);
        if (f.exists()) f.delete();
    }

    /**
     * List every credential stored (one per user).
     * Scans the data directory for creds_*.xml files.
     */
    public List<Credential> loadAllCredentials() {
        List<Credential> list = new ArrayList<>();
        File[] files = dataDir.listFiles(
            (dir, name) -> name.startsWith("creds_") && name.endsWith(".xml"));
        if (files == null) return list;
        for (File f : files) {
            try {
                Document doc = parseXml(f);
                NodeList nodes = doc.getElementsByTagName("credential");
                for (int i = 0; i < nodes.getLength(); i++) {
                    list.add(elementToCredential((Element) nodes.item(i)));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return list;
    }

    // ─── Tasks ───────────────────────────────────────────────────────────────

    public List<ScheduledTask> loadTasks() {
        synchronized (taskFileLock) {
            List<ScheduledTask> list = new ArrayList<>();
            if (!taskFile.exists()) return list;
            try {
                Document doc = parseXml(taskFile);
                NodeList nodes = doc.getElementsByTagName("task");
                for (int i = 0; i < nodes.getLength(); i++) {
                    try {
                        Element e = (Element) nodes.item(i);
                        ScheduledTask t = new ScheduledTask();
                        t.setId(attr(e, "id"));
                        t.setName(child(e, "name"));
                        t.setTaskType(TaskType.valueOf(child(e, "taskType")));
                        t.setStatus(TaskStatus.valueOf(child(e, "status")));
                        t.setSourceCredentialId(child(e, "sourceCredentialId"));
                        t.setTargetCredentialId(child(e, "targetCredentialId"));
                        t.setSourcePath(child(e, "sourcePath"));
                        t.setTargetPath(child(e, "targetPath"));
                        t.setAdditionalTargetPaths(child(e, "additionalTargetPaths"));
                        t.setBackupSourcePath(child(e, "backupSourcePath"));
                        t.setBackupDestinationPath(child(e, "backupDestinationPath"));
                        String backupRetention = child(e, "backupRetentionDays");
                        if (backupRetention != null && !backupRetention.isEmpty()) {
                            try {
                                t.setBackupRetentionDays(Integer.parseInt(backupRetention));
                            } catch (NumberFormatException ignored) {
                                t.setBackupRetentionDays(3);
                            }
                        }
                        t.setBackupSourceUsername(child(e, "backupSourceUsername"));
                        t.setBackupDestinationUsername(child(e, "backupDestinationUsername"));
                        t.setImapFolder(child(e, "imapFolder"));
                        t.setMailSearchCriteria(child(e, "mailSearchCriteria"));
                        String fetchMode = child(e, "mailFetchMode");
                        if (fetchMode == null || fetchMode.isEmpty()) fetchMode = "BODY_ONLY";
                        t.setMailFetchMode(MailFetchMode.valueOf(fetchMode));
                        t.setMailMailboxAddress(child(e, "mailMailboxAddress"));
                        String mailTenant = child(e, "mailTenantId");
                        t.setMailTenantId(mailTenant != null && !mailTenant.isEmpty() ? mailTenant : "common");
                        t.setMailClientId(child(e, "mailClientId"));
                        String fetchScope = child(e, "mailFetchScope");
                        t.setMailFetchScope(fetchScope != null && !fetchScope.isEmpty()
                                ? ScheduledTask.MailFetchScope.valueOf(fetchScope)
                                : ScheduledTask.MailFetchScope.LATEST_ONLY);
                        String maxResults = child(e, "mailMaxResults");
                        try {
                            t.setMailMaxResults(maxResults != null && !maxResults.isEmpty()
                                    ? Integer.parseInt(maxResults) : 50);
                        } catch (NumberFormatException nfe) {
                            t.setMailMaxResults(50);
                        }
                        String mailEpoch = child(e, "mailLastKnownEpoch");
                        try {
                            t.setMailLastKnownEpoch(mailEpoch != null && !mailEpoch.isEmpty()
                                    ? Long.parseLong(mailEpoch) : 0L);
                        } catch (NumberFormatException nfe) {
                            t.setMailLastKnownEpoch(0L);
                        }
                        t.setMailMarkAsRead("true".equalsIgnoreCase(child(e, "mailMarkAsRead")));
                        t.setMailMoveToFolderEnabled("true".equalsIgnoreCase(child(e, "mailMoveToFolderEnabled")));
                        t.setMailMoveToFolderName(child(e, "mailMoveToFolderName"));
                        t.setMailOutputFolder(child(e, "mailOutputFolder"));
                        String direction = child(e, "transferDirection");
                        if (direction == null || direction.isEmpty()) direction = "OUTBOUND";
                        t.setTransferDirection(TransferDirection.valueOf(direction));
                        String mode = child(e, "transferMode");
                        if (mode == null || mode.isEmpty()) mode = "ENTIRE_FOLDER";
                        t.setTransferMode(TransferMode.valueOf(mode));
                        t.setScheduleType(ScheduleType.valueOf(child(e, "scheduleType")));
                        String sat = child(e, "scheduledAt");
                        if (sat != null && !sat.isEmpty()) t.setScheduledAt(LocalDateTime.parse(sat, DT_FMT));
                        String intervalMinutes = child(e, "intervalMinutes");
                        if (intervalMinutes != null && !intervalMinutes.isEmpty()) {
                            try {
                                t.setIntervalMinutes(Integer.parseInt(intervalMinutes));
                            } catch (NumberFormatException ignored) {
                                t.setIntervalMinutes(0);
                            }
                        }
                        String intervalSeconds = child(e, "intervalSeconds");
                        if (intervalSeconds != null && !intervalSeconds.isEmpty()) {
                            try {
                                t.setIntervalSeconds(Integer.parseInt(intervalSeconds));
                            } catch (NumberFormatException ignored) {
                                t.setIntervalSeconds(0);
                            }
                        }
                        t.setCronExpression(child(e, "cronExpression"));
                        String inboundWatcherPollInterval = child(e, "inboundWatcherPollIntervalMinutes");
                        if (inboundWatcherPollInterval != null && !inboundWatcherPollInterval.isEmpty()) {
                            try {
                                t.setInboundWatcherPollIntervalMinutes(Integer.parseInt(inboundWatcherPollInterval));
                            } catch (NumberFormatException ignored) {
                                t.setInboundWatcherPollIntervalMinutes(0);
                            }
                        }
                        String lastRun = child(e, "lastRunAt");
                        if (lastRun != null && !lastRun.isEmpty()) t.setLastRunAt(LocalDateTime.parse(lastRun, DT_FMT));
                        String lastStarted = child(e, "lastStartedAt");
                        if (lastStarted != null && !lastStarted.isEmpty())
                            t.setLastStartedAt(LocalDateTime.parse(lastStarted, DT_FMT));
                        t.setLastRunResult(child(e, "lastRunResult"));
                        String created = child(e, "createdAt");
                        if (created != null && !created.isEmpty()) t.setCreatedAt(LocalDateTime.parse(created, DT_FMT));
                        String retryCount = child(e, "retryCount");
                        if (retryCount != null && !retryCount.isEmpty()) {
                            try {
                                t.setRetryCount(Integer.parseInt(retryCount));
                            } catch (NumberFormatException ignored) {
                                t.setRetryCount(0);
                            }
                        }
                        // Support both legacy 'inboundWatcherEnabled' and new 'watcherEnabled' tags
                        String watcherFlag = child(e, "watcherEnabled");
                        if (watcherFlag == null) watcherFlag = child(e, "inboundWatcherEnabled");
                        if (watcherFlag != null && !watcherFlag.isEmpty()) {
                            t.setWatcherEnabled(Boolean.parseBoolean(watcherFlag));
                        }
                        String watcherAge = child(e, "inboundWatcherMaxAgeMinutes");
                        if (watcherAge != null && !watcherAge.isEmpty()) {
                            try {
                                t.setInboundWatcherMaxAgeMinutes(Integer.parseInt(watcherAge));
                            } catch (NumberFormatException ignored) {
                                t.setInboundWatcherMaxAgeMinutes(0);
                            }
                        }
                        // New fields added for simplified ops workflow
                        t.setTargetUsername(child(e, "targetUsername"));
                        String epoch = child(e, "lastKnownRemoteFileEpoch");
                        if (epoch != null && !epoch.isEmpty()) {
                            try {
                                t.setLastKnownRemoteFileEpoch(Long.parseLong(epoch));
                            } catch (NumberFormatException ignored) {
                                t.setLastKnownRemoteFileEpoch(0L);
                            }
                        }
                        String knownSize = child(e, "lastKnownRemoteFileSize");
                        if (knownSize != null && !knownSize.isEmpty()) {
                            try {
                                t.setLastKnownRemoteFileSize(Long.parseLong(knownSize));
                            } catch (NumberFormatException ignored) {
                                t.setLastKnownRemoteFileSize(-1L);
                            }
                        }
                        list.add(t);
                    } catch (IllegalArgumentException enumEx) {
                        // Most commonly a stored enum value (e.g. an old taskType such as
                        // a removed "STOP_SERVICE" service-task type from a previous
                        // version) that no longer exists in this build. This is expected
                        // when upgrading in place — the task is simply skipped rather than
                        // aborting the whole load; no stack trace needed for this case.
                        System.err.println("[WARN] Skipping legacy/unrecognized task at index " + i
                                + " — no longer supported by this version: " + enumEx.getMessage());
                    } catch (Exception taskEx) {
                        // Log and skip only this task — don't let one corrupt
                        // <task> element wipe out every other task on save.
                        System.err.println("Skipping unparseable task at index " + i + ": " + taskEx.getMessage());
                        taskEx.printStackTrace();
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException("Failed to load tasks.xml — aborting to avoid data loss", ex);
            }
            return list;
        }
    }

    public void saveTask(ScheduledTask task) {
        synchronized (taskFileLock) {
            if (task.getId() == null || task.getId().isEmpty()) {
                task.setId(UUID.randomUUID().toString());
            }
            List<ScheduledTask> list;
            try {
                list = loadTasks();
            } catch (RuntimeException ex) {
                // loadTasks() failed at the file level — do NOT proceed to write,
                // that would overwrite a possibly-fine file with an empty one.
                System.err.println("Refusing to save: could not safely reload tasks.xml first.");
                throw ex;
            }
            list.removeIf(t -> t.getId().equals(task.getId()));
            list.add(task);
            writeTasks(list);
        }
    }

    public void deleteTask(String id) {
        synchronized (taskFileLock) {
            List<ScheduledTask> list = loadTasks();
            list.removeIf(t -> t.getId().equals(id));
            writeTasks(list);
        }
    }

    private void writeTasks(List<ScheduledTask> list) {
        synchronized (taskFileLock) {
            try {
                // ── 1. Build the in-memory XML document ─────────────────────────
                Document doc = newDoc("tasks");
                Element root = doc.getDocumentElement();
                for (ScheduledTask t : list) {
                    Element e = doc.createElement("task");
                    e.setAttribute("id", t.getId());
                    addChild(doc, e, "name", t.getName());
                    addChild(doc, e, "taskType", t.getTaskType().name());
                    addChild(doc, e, "status", t.getStatus().name());
                    addChild(doc, e, "sourceCredentialId", t.getSourceCredentialId());
                    addChild(doc, e, "targetCredentialId", t.getTargetCredentialId());
                    addChild(doc, e, "targetUsername", t.getTargetUsername());
                    addChild(doc, e, "transferDirection", t.getTransferDirection() != null
                            ? t.getTransferDirection().name() : TransferDirection.OUTBOUND.name());
                    addChild(doc, e, "transferMode", t.getTransferMode() != null
                            ? t.getTransferMode().name() : TransferMode.ENTIRE_FOLDER.name());
                    addChild(doc, e, "sourcePath", t.getSourcePath());
                    addChild(doc, e, "targetPath", t.getTargetPath());
                    addChild(doc, e, "additionalTargetPaths", t.getAdditionalTargetPaths());
                    addChild(doc, e, "backupSourcePath", t.getBackupSourcePath());
                    addChild(doc, e, "backupDestinationPath", t.getBackupDestinationPath());
                    addChild(doc, e, "backupRetentionDays", String.valueOf(t.getBackupRetentionDays()));
                    addChild(doc, e, "backupSourceUsername", t.getBackupSourceUsername());
                    addChild(doc, e, "backupDestinationUsername", t.getBackupDestinationUsername());
                    addChild(doc, e, "imapFolder", t.getImapFolder());
                    addChild(doc, e, "mailSearchCriteria", t.getMailSearchCriteria());
                    addChild(doc, e, "mailFetchMode", t.getMailFetchMode() != null
                            ? t.getMailFetchMode().name() : MailFetchMode.BODY_ONLY.name());
                    addChild(doc, e, "mailMailboxAddress", t.getMailMailboxAddress());
                    addChild(doc, e, "mailTenantId", t.getMailTenantId() != null ? t.getMailTenantId() : "common");
                    addChild(doc, e, "mailClientId", t.getMailClientId());
                    addChild(doc, e, "mailFetchScope", t.getMailFetchScope() != null
                            ? t.getMailFetchScope().name() : ScheduledTask.MailFetchScope.LATEST_ONLY.name());
                    addChild(doc, e, "mailMaxResults", String.valueOf(
                            t.getMailMaxResults() > 0 ? t.getMailMaxResults() : 50));
                    addChild(doc, e, "mailLastKnownEpoch", String.valueOf(t.getMailLastKnownEpoch()));
                    addChild(doc, e, "mailMarkAsRead", String.valueOf(t.isMailMarkAsRead()));
                    addChild(doc, e, "mailMoveToFolderEnabled", String.valueOf(t.isMailMoveToFolderEnabled()));
                    addChild(doc, e, "mailMoveToFolderName", t.getMailMoveToFolderName());
                    addChild(doc, e, "mailOutputFolder", t.getMailOutputFolder());
                    addChild(doc, e, "scheduleType", t.getScheduleType().name());
                    addChild(doc, e, "scheduledAt", t.getScheduledAt() != null ? t.getScheduledAt().format(DT_FMT) : "");
                    addChild(doc, e, "intervalMinutes", String.valueOf(t.getIntervalMinutes()));
                    addChild(doc, e, "intervalSeconds", String.valueOf(t.getIntervalSeconds()));
                    addChild(doc, e, "cronExpression", t.getCronExpression() != null ? t.getCronExpression() : "");
                    addChild(doc, e, "lastRunAt", t.getLastRunAt() != null ? t.getLastRunAt().format(DT_FMT) : "");
                    addChild(doc, e, "lastStartedAt", t.getLastStartedAt() != null ? t.getLastStartedAt().format(DT_FMT) : "");
                    addChild(doc, e, "lastRunResult", t.getLastRunResult() != null ? t.getLastRunResult() : "");
                    addChild(doc, e, "createdAt", t.getCreatedAt() != null ? t.getCreatedAt().format(DT_FMT) : "");
                    addChild(doc, e, "retryCount", String.valueOf(t.getRetryCount()));
                    addChild(doc, e, "watcherEnabled", String.valueOf(t.isWatcherEnabled()));
                    addChild(doc, e, "inboundWatcherEnabled", String.valueOf(t.isWatcherEnabled()));
                    addChild(doc, e, "inboundWatcherPollIntervalMinutes", String.valueOf(t.getInboundWatcherPollIntervalMinutes()));
                    addChild(doc, e, "inboundWatcherMaxAgeMinutes", String.valueOf(t.getInboundWatcherMaxAgeMinutes()));
                    addChild(doc, e, "lastKnownRemoteFileEpoch", String.valueOf(t.getLastKnownRemoteFileEpoch()));
                    addChild(doc, e, "lastKnownRemoteFileSize", String.valueOf(t.getLastKnownRemoteFileSize()));
                    root.appendChild(e);
                }

                // ── 2. Write to a temp file first — never touch tasks.xml directly ──
                File tmp = new File(dataDir, "tasks.xml.tmp");
                writeXml(doc, tmp);

                // ── 3. Back up the current tasks.xml (if any) before replacing it ──
                File backup = new File(dataDir, "tasks.xml.bak");
                if (taskFile.exists()) {
                    if (backup.exists() && !backup.delete()) {
                        throw new java.io.IOException("Could not remove stale tasks.xml.bak");
                    }
                    if (!taskFile.renameTo(backup)) {
                        throw new java.io.IOException("Could not back up tasks.xml to tasks.xml.bak");
                    }
                }

                // ── 4. Atomically promote the temp file to be the real tasks.xml ──
                if (!tmp.renameTo(taskFile)) {
                    // Something went wrong mid-swap — try to restore the backup
                    // so we don't end up with neither a valid tasks.xml nor a way
                    // to recover the previous state.
                    if (backup.exists()) {
                        backup.renameTo(taskFile);
                    }
                    throw new java.io.IOException("Failed to move tasks.xml.tmp into place");
                }

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    // ─── XML helpers ─────────────────────────────────────────────────────────

    private Credential elementToCredential(Element e) {
        Credential c = new Credential();
        c.setId(attr(e, "id"));
        c.setName(child(e, "name"));
        c.setHost(child(e, "host"));
        c.setUsername(child(e, "username"));
        c.setPassword(child(e, "password"));
        c.setOsType(child(e, "osType"));
        return c;
    }

    private Element credentialToElement(Document doc, Credential c) {
        Element e = doc.createElement("credential");
        e.setAttribute("id", c.getId());
        addChild(doc, e, "name",     c.getName());
        addChild(doc, e, "host",     c.getHost());
        addChild(doc, e, "username", c.getUsername());
        addChild(doc, e, "password", c.getPassword());    // plain text
        addChild(doc, e, "osType",   c.getOsType());
        return e;
    }

    private Document parseXml(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return dbf.newDocumentBuilder().parse(f);
    }

    private Document newDoc(String rootTag) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().newDocument();
        doc.appendChild(doc.createElement(rootTag));
        return doc;
    }

    private void writeXml(Document doc, File f) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.transform(new DOMSource(doc), new StreamResult(f));
    }

    private String attr(Element e, String name)  { return e.getAttribute(name); }

    private String child(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent();
    }

    private void addChild(Document doc, Element parent, String tag, String value) {
        Element e = doc.createElement(tag);
        e.setTextContent(value != null ? value : "");
        parent.appendChild(e);
    }
}
