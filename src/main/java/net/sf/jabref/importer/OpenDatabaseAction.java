/*  Copyright (C) 2003-2015 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package net.sf.jabref.importer;

import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.swing.Action;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import net.sf.jabref.*;
import net.sf.jabref.exporter.AutoSaveManager;
import net.sf.jabref.exporter.SaveSession;
import net.sf.jabref.gui.*;
import net.sf.jabref.gui.actions.MnemonicAwareAction;
import net.sf.jabref.gui.keyboard.KeyBinds;
import net.sf.jabref.migrations.FileLinksUpgradeWarning;
import net.sf.jabref.importer.fileformat.BibtexParser;
import net.sf.jabref.logic.l10n.Localization;
import net.sf.jabref.model.database.BibtexDatabase;
import net.sf.jabref.model.entry.BibtexEntry;
import net.sf.jabref.specialfields.SpecialFieldsUtils;
import net.sf.jabref.logic.util.io.FileBasedLock;
import net.sf.jabref.logic.util.strings.StringUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

// The action concerned with opening an existing database.

public class OpenDatabaseAction extends MnemonicAwareAction {

    private static final Log LOGGER = LogFactory.getLog(OpenDatabaseAction.class);

    private final boolean showDialog;
    private final JabRefFrame frame;

    // List of actions that may need to be called after opening the file. Such as
    // upgrade actions etc. that may depend on the JabRef version that wrote the file:
    private static final ArrayList<PostOpenAction> postOpenActions = new ArrayList<>();


    static {
        // Add the action for checking for new custom entry types loaded from
        // the bib file:
        OpenDatabaseAction.postOpenActions.add(new CheckForNewEntryTypesAction());
        // Add the action for the new external file handling system in version 2.3:
        OpenDatabaseAction.postOpenActions.add(new FileLinksUpgradeWarning());
        // Add the action for warning about and handling duplicate BibTeX keys:
        OpenDatabaseAction.postOpenActions.add(new HandleDuplicateWarnings());
    }

    public OpenDatabaseAction(JabRefFrame frame, boolean showDialog) {
        super(IconTheme.JabRefIcon.OPEN.getIcon());
        this.frame = frame;
        this.showDialog = showDialog;
        putValue(Action.NAME, Localization.menuTitle("Open database"));
        putValue(Action.ACCELERATOR_KEY, Globals.prefs.getKey(KeyBinds.OPEN_DATABASE));
        putValue(Action.SHORT_DESCRIPTION, Localization.lang("Open BibTeX database"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<File> filesToOpen = new ArrayList<>();

        if (showDialog) {
            String[] chosen = FileDialogs.getMultipleFiles(frame,
                    new File(Globals.prefs.get(JabRefPreferences.WORKING_DIRECTORY)), ".bib", true);
            if (chosen != null) {
                for (String aChosen : chosen) {
                    if (aChosen != null) {
                        filesToOpen.add(new File(aChosen));
                    }
                }
            }
        } else {
            LOGGER.info(Action.NAME + " " + e.getActionCommand());
            filesToOpen.add(new File(StringUtil.getCorrectFileName(e.getActionCommand(), "bib")));
        }

        openFiles(filesToOpen, true);
    }


    class OpenItSwingHelper implements Runnable {

        final BasePanel bp;
        final boolean raisePanel;
        final File file;


        OpenItSwingHelper(BasePanel bp, File file, boolean raisePanel) {
            this.bp = bp;
            this.raisePanel = raisePanel;
            this.file = file;
        }

        @Override
        public void run() {
            frame.addTab(bp, file, raisePanel);

        }
    }


    /**
     * Opens the given file. If null or 404, nothing happens
     *
     * @param file the file, may be null or not existing
     */
    public void openFile(File file, boolean raisePanel) {
        List<File> filesToOpen = new ArrayList<>();
        filesToOpen.add(file);
        openFiles(filesToOpen, raisePanel);
    }

    public void openFilesAsStringList(List<String> fileNamesToOpen, boolean raisePanel) {
        List<File> filesToOpen = new ArrayList<>();
        for (String fileName : fileNamesToOpen) {
            filesToOpen.add(new File(fileName));
        }
        openFiles(filesToOpen, raisePanel);
    }

    /**
     * Opens the given files. If one of it is null or 404, nothing happens
     *
     * @param filesToOpen the filesToOpen, may be null or not existing
     */
    public void openFiles(List<File> filesToOpen, boolean raisePanel) {
        BasePanel toRaise = null;
        int initialCount = filesToOpen.size();
        int removed = 0;

        // Check if any of the files are already open:
        for (Iterator<File> iterator = filesToOpen.iterator(); iterator.hasNext();) {
            File file = iterator.next();
            for (int i = 0; i < frame.getTabbedPane().getTabCount(); i++) {
                BasePanel bp = frame.getBasePanelAt(i);
                if ((bp.getDatabaseFile() != null) && bp.getDatabaseFile().equals(file)) {
                    iterator.remove();
                    removed++;
                    // See if we removed the final one. If so, we must perhaps
                    // raise the BasePanel in question:
                    if (removed == initialCount) {
                        toRaise = bp;
                    }
                    // no more bps to check, we found a matching one
                    break;
                }
            }
        }

        // Run the actual open in a thread to prevent the program
        // locking until the file is loaded.
        if (!filesToOpen.isEmpty()) {
            final List<File> theFiles = Collections.unmodifiableList(filesToOpen);
            JabRefExecutorService.INSTANCE.execute(new Runnable() {

                @Override
                public void run() {
                    for (File theFile : theFiles) {
                        openTheFile(theFile, raisePanel);
                    }
                }
            });
            for (File theFile : theFiles) {
                frame.getFileHistory().newFile(theFile.getPath());
            }
        }
        // If no files are remaining to open, this could mean that a file was
        // already open. If so, we may have to raise the correct tab:
        else if (toRaise != null) {
            frame.output(Localization.lang("File '%0' is already open.", toRaise.getDatabaseFile().getPath()));
            frame.getTabbedPane().setSelectedComponent(toRaise);
        }

        frame.output(Localization.lang("Files opened") + ": " + (filesToOpen.size()));
    }

    /**
     * @param file the file, may be null or not existing
     */
    private void openTheFile(File file, boolean raisePanel) {
        if ((file != null) && file.exists()) {
            File fileToLoad = file;
            frame.output(Localization.lang("Opening") + ": '" + file.getPath() + "'");
            boolean tryingAutosave = false;
            boolean autoSaveFound = AutoSaveManager.newerAutoSaveExists(file);
            if (autoSaveFound && !Globals.prefs.getBoolean(JabRefPreferences.PROMPT_BEFORE_USING_AUTOSAVE)) {
                // We have found a newer autosave, and the preferences say we should load
                // it without prompting, so we replace the fileToLoad:
                fileToLoad = AutoSaveManager.getAutoSaveFile(file);
                tryingAutosave = true;
            } else if (autoSaveFound) {
                // We have found a newer autosave, but we are not allowed to use it without
                // prompting.
                int answer = JOptionPane.showConfirmDialog(null,
                        "<html>" + Localization
                                .lang("An autosave file was found for this database. This could indicate ")
                        + Localization.lang("that JabRef didn't shut down cleanly last time the file was used.")
                        + "<br>" + Localization.lang("Do you want to recover the database from the autosave file?")
                        + "</html>", Localization.lang("Recover from autosave"), JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) {
                    fileToLoad = AutoSaveManager.getAutoSaveFile(file);
                    tryingAutosave = true;
                }
            }

            boolean done = false;
            while (!done) {
                String fileName = file.getPath();
                Globals.prefs.put(JabRefPreferences.WORKING_DIRECTORY, file.getPath());
                // Should this be done _after_ we know it was successfully opened?
                String encoding = Globals.prefs.get(JabRefPreferences.DEFAULT_ENCODING);

                if (FileBasedLock.hasLockFile(file)) {
                    long modTime = FileBasedLock.getLockFileTimeStamp(file);
                    if ((modTime != -1)
                            && ((System.currentTimeMillis() - modTime) > SaveSession.LOCKFILE_CRITICAL_AGE)) {
                        // The lock file is fairly old, so we can offer to "steal" the file:
                        int answer = JOptionPane.showConfirmDialog(null,
                                "<html>" + Localization.lang("Error opening file") + " '" + fileName + "'. "
                                        + Localization.lang("File is locked by another JabRef instance.") + "<p>"
                                        + Localization.lang("Do you want to override the file lock?"),
                                Localization.lang("File locked"), JOptionPane.YES_NO_OPTION);
                        if (answer == JOptionPane.YES_OPTION) {
                            FileBasedLock.deleteLockFile(file);
                        } else {
                            return;
                        }
                    } else if (!FileBasedLock.waitForFileLock(file, 10)) {
                        JOptionPane.showMessageDialog(null,
                                Localization.lang("Error opening file") + " '" + fileName + "'. "
                                        + Localization.lang("File is locked by another JabRef instance."),
                                Localization.lang("Error"), JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                }
                ParserResult pr;
                String errorMessage = null;
                try {
                    pr = OpenDatabaseAction.loadDatabase(fileToLoad, encoding);
                } catch (Exception ex) {
                    //ex.printStackTrace();
                    errorMessage = ex.getMessage();
                    pr = null;
                }
                if ((pr == null) || (pr == ParserResult.INVALID_FORMAT)) {
                    JOptionPane.showMessageDialog(null, Localization.lang("Error opening file") + " '" + fileName + "'",
                            Localization.lang("Error"), JOptionPane.ERROR_MESSAGE);

                    String message = "<html>" + errorMessage + "<p>"
                            + (tryingAutosave ? Localization.lang(
                                    "Error opening autosave of '%0'. Trying to load '%0' instead.",
                                    file.getName()) : ""/*Globals.lang("Error opening file '%0'.", file.getName())*/)
                            + "</html>";
                    JOptionPane.showMessageDialog(null, message, Localization.lang("Error opening file"),
                            JOptionPane.ERROR_MESSAGE);

                    if (tryingAutosave) {
                        tryingAutosave = false;
                        fileToLoad = file;
                    } else {
                        done = true;
                    }
                    continue;
                } else {
                    done = true;
                }

                final BasePanel panel = addNewDatabase(pr, file, raisePanel);
                if (tryingAutosave) {
                    panel.markNonUndoableBaseChanged();
                }

                // After adding the database, go through our list and see if
                // any post open actions need to be done. For instance, checking
                // if we found new entry types that can be imported, or checking
                // if the database contents should be modified due to new features
                // in this version of JabRef:
                final ParserResult prf = pr;
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        OpenDatabaseAction.performPostOpenActions(panel, prf, true);
                    }
                });
            }

        }
    }

    /**
     * Go through the list of post open actions, and perform those that need to be performed.
     *
     * @param panel The BasePanel where the database is shown.
     * @param pr The result of the bib file parse operation.
     */
    public static void performPostOpenActions(BasePanel panel, ParserResult pr, boolean mustRaisePanel) {
        for (PostOpenAction action : OpenDatabaseAction.postOpenActions) {
            if (action.isActionNecessary(pr)) {
                if (mustRaisePanel) {
                    panel.frame().getTabbedPane().setSelectedComponent(panel);
                }
                action.performAction(panel, pr);
            }
        }
    }

    public BasePanel addNewDatabase(ParserResult pr, final File file, boolean raisePanel) {

        String fileName = file.getPath();
        BibtexDatabase db = pr.getDatabase();
        MetaData meta = pr.getMetaData();

        if (pr.hasWarnings()) {
            final String[] wrns = pr.warnings();
            JabRefExecutorService.INSTANCE.execute(new Runnable() {

                @Override
                public void run() {
                    StringBuilder wrn = new StringBuilder();
                    for (int i = 0; i < wrns.length; i++) {
                        wrn.append(i + 1).append(". ").append(wrns[i]).append("\n");
                    }

                    if (wrn.length() > 0) {
                        wrn.deleteCharAt(wrn.length() - 1);
                    }
                    // Note to self or to someone else: The following line causes an
                    // ArrayIndexOutOfBoundsException in situations with a large number of
                    // warnings; approx. 5000 for the database I opened when I observed the problem
                    // (duplicate key warnings). I don't think this is a big problem for normal situations,
                    // and it may possibly be a bug in the Swing code.
                    JOptionPane.showMessageDialog(frame, wrn.toString(),
                            Localization.lang("Warnings") + " (" + file.getName() + ")", JOptionPane.WARNING_MESSAGE);
                }
            });
        }
        BasePanel bp = new BasePanel(frame, db, file, meta, pr.getEncoding());

        // file is set to null inside the EventDispatcherThread
        SwingUtilities.invokeLater(new OpenItSwingHelper(bp, file, raisePanel));

        frame.output(Localization.lang("Opened database") + " '" + fileName + "' " + Localization.lang("with") + " "
                + db.getEntryCount() + " " + Localization.lang("entries") + ".");

        return bp;
    }

    /**
     * Opens a new database.
     */
    public static ParserResult loadDatabase(File fileToOpen, String fallbackEncoding) throws IOException {

        // We want to check if there is a JabRef signature in the file, because that would tell us
        // which character encoding is used. However, to read the signature we must be using a compatible
        // encoding in the first place. Since the signature doesn't contain any fancy characters, we can
        // read it regardless of encoding, with either UTF-8 or UTF-16. That's the hypothesis, at any rate.
        // 8 bit is most likely, so we try that first:
        Optional<String> suppliedEncoding = Optional.empty();
        try (Reader utf8Reader = ImportFormatReader.getUTF8Reader(fileToOpen)) {
            suppliedEncoding = OpenDatabaseAction.getSuppliedEncoding(utf8Reader);
        }
        // Now if that didn't get us anywhere, we check with the 16 bit encoding:
        if (!suppliedEncoding.isPresent()) {
            try (Reader utf16Reader = ImportFormatReader.getUTF16Reader(fileToOpen)) {
                suppliedEncoding = OpenDatabaseAction.getSuppliedEncoding(utf16Reader);
            }
        }

        // Open and parse file
        try (InputStreamReader reader = openFile(fileToOpen, suppliedEncoding, fallbackEncoding)) {
            BibtexParser bp = new BibtexParser(reader);

            ParserResult pr = bp.parse();
            pr.setEncoding(reader.getEncoding());
            pr.setFile(fileToOpen);

            if (SpecialFieldsUtils.keywordSyncEnabled()) {
                for (BibtexEntry entry : pr.getDatabase().getEntries()) {
                    SpecialFieldsUtils.syncSpecialFieldsFromKeywords(entry, null);
                }
                LOGGER.info("Synchronized special fields based on keywords");
            }

            if (!pr.getMetaData().isGroupTreeValid()) {
                pr.addWarning(Localization.lang(
                        "Group tree could not be parsed. If you save the BibTeX database, all groups will be lost."));
            }

            return pr;
        }
    }

    /**
     * Opens the file with the provided encoding. If this fails (or no encoding is provided), then the fallback encoding
     * will be used.
     */
    private static InputStreamReader openFile(File fileToOpen, Optional<String> encoding, String fallbackEncoding)
            throws IOException {
        if (encoding.isPresent()) {
            try {
                return ImportFormatReader.getReader(fileToOpen, encoding.get());
            } catch (Exception ex) {
                ex.printStackTrace();
                // The supplied encoding didn't work out, so we use the fallback.
                return ImportFormatReader.getReader(fileToOpen, fallbackEncoding);
            }
        } else {
            // We couldn't find a header with info about encoding. Use fallback:
            return ImportFormatReader.getReader(fileToOpen, fallbackEncoding);

        }
    }

    /**
     * Searches the file for "Encoding: myEncoding" and returns the found supplied encoding.
     */
    private static Optional<String> getSuppliedEncoding(Reader reader) {
        try {
            BufferedReader br = new BufferedReader(reader);
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();

                // Line does not start with %, so there are no comment lines for us and we can stop parsing
                if (!line.startsWith("%")) {
                    return Optional.empty();
                }

                // Only keep the part after %
                line = line.substring(1).trim();

                if (line.startsWith(Globals.SIGNATURE)) {
                    // Signature line, so keep reading and skip to next line
                } else if (line.startsWith(Globals.encPrefix)) {
                    // Line starts with "Encoding: ", so the rest of the line should contain the name of the encoding
                    return Optional.of(line.substring(Globals.encPrefix.length()).trim());
                } else {
                    // Line not recognized so stop parsing
                    return Optional.empty();
                }
            }
        } catch (IOException ignored) {
            // Ignored
        }
        return Optional.empty();
    }
}