package dev.mvswitcher;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class MVSwitcherInterface {
    private final JFrame frame = new JFrame("MVSwitcher");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField inputField = new JTextField();
    private final JTextField fromField = new JTextField();
    private final JComboBox<String> toField = new JComboBox<>();
    private final JTextField neoField = new JTextField();
    private final JSpinner javaField = new JSpinner(new SpinnerNumberModel(21, 8, 99, 1));
    private final JTextField outputField = new JTextField();
    private final JTextField rulesField = new JTextField();
    private final JTextField templateField = new JTextField();
    private final JCheckBox dryRunField = new JCheckBox("Только план (dry-run)");
    private final JCheckBox forceField = new JCheckBox("Заменить существующий каталог (--force)");
    private final JCheckBox skipBuildField = new JCheckBox("Пропустить сборки (--skip-build)");
    private final JButton migrateButton = new JButton("Начать миграцию");
    private final JButton analyzeButton = new JButton("Анализировать проект");
    private final JButton versionsButton = new JButton("Показать версии и маршруты");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Готово");
    private final JTextPane logPane = new JTextPane();
    private final StringBuilder savedLog = new StringBuilder();
    private final JTextArea resultArea = new JTextArea();
    private final JTextArea versionsArea = new JTextArea();
    private boolean busy;

    public void show() {
        SwingUtilities.invokeLater(this::createAndShow);
    }

    private void createAndShow() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(950, 720));
        frame.setSize(1180, 900);
        frame.setLocationByPlatform(true);

        tabs.addTab("Миграция", createMigrationPanel());
        tabs.addTab("Анализ проекта", createAnalysisPanel());
        tabs.addTab("Версии и правила", createVersionsPanel());
        tabs.addTab("Отчёт", createResultPanel());

        JPanel logPanel = createLogPanel();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logPanel);
        split.setResizeWeight(0.58);
        split.setDividerLocation(520);
        frame.add(split, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        progress.setIndeterminate(false);
        progress.setPreferredSize(new Dimension(180, 18));
        statusPanel.add(status, BorderLayout.CENTER);
        statusPanel.add(progress, BorderLayout.EAST);
        frame.add(statusPanel, BorderLayout.SOUTH);

        loadProfiles();
        appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.INFO,
                "MVSwitcher интерфейс готов. Выберите проект и параметры операции."));
        frame.setVisible(true);
    }

    private JPanel createMigrationPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int row = 0;
        addPathRow(form, c, row++, "Исходный проект / ZIP:", inputField, "Выбрать проект", false);

        fromField.setToolTipText("Оставьте пустым, чтобы определить исходную версию из проекта.");
        addRow(form, c, row++, "Исходная версия:", withHint(fromField, "Пусто — определить автоматически"));

        toField.setEditable(true);
        VersionCatalog.all().forEach(profile -> toField.addItem(profile.minecraft()));
        toField.setSelectedItem(VersionCatalog.all().get(VersionCatalog.all().size() - 1).minecraft());
        toField.addActionListener(event -> applyProfileDefaults());
        applyProfileDefaults();
        addRow(form, c, row++, "Целевая версия Minecraft:", toField);

        JPanel targetDetails = new JPanel(new GridBagLayout());
        GridBagConstraints d = new GridBagConstraints();
        d.insets = new Insets(0, 0, 0, 8);
        d.fill = GridBagConstraints.HORIZONTAL;
        d.weightx = 0;
        targetDetails.add(new JLabel("NeoForge:"), d);
        d.weightx = 1;
        d.gridx = 1;
        targetDetails.add(neoField, d);
        d.gridx = 2;
        d.weightx = 0;
        targetDetails.add(new JLabel("Java:"), d);
        d.gridx = 3;
        d.weightx = 0;
        targetDetails.add(javaField, d);
        addRow(form, c, row++, "Целевые параметры:", targetDetails);

        addPathRow(form, c, row++, "Каталог результата:", outputField, "Выбрать каталог", true);
        addPathRow(form, c, row++, "Правила (JSON/каталог, необязательно):",
                rulesField, "Выбрать правила", false);
        addPathRow(form, c, row++, "Шаблон NeoForge MDK (необязательно):",
                templateField, "Выбрать шаблон", false);

        JPanel flags = new JPanel(new GridLayout(0, 1, 3, 3));
        flags.setBorder(BorderFactory.createTitledBorder("Опции"));
        flags.add(dryRunField);
        flags.add(forceField);
        flags.add(skipBuildField);
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(flags, c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        analyzeButton.addActionListener(event -> analyzeSelectedProject());
        migrateButton.addActionListener(event -> migrate());
        versionsButton.addActionListener(event -> showVersions());
        actions.add(analyzeButton);
        actions.add(migrateButton);
        actions.add(versionsButton);
        c.gridy = row;
        form.add(actions, c);
        c.gridy = row + 1;
        c.weighty = 1;
        form.add(new JLabel("<html><p>Исходный проект не изменяется. Результат создаётся отдельно. " +
                "Для неизвестной целевой версии задайте NeoForge вручную. Все этапы и вывод Gradle " +
                "появляются в журнале ниже.</p></html>"), c);
        JPanel panel = new JPanel(new BorderLayout());
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(new JLabel("<html>Анализируется путь, указанный на вкладке «Миграция». " +
                "Можно выбрать папку проекта или ZIP-архив.</html>"), BorderLayout.NORTH);
        JButton run = new JButton("Анализировать выбранный проект");
        run.addActionListener(event -> analyzeSelectedProject());
        panel.add(run, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createVersionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        versionsArea.setEditable(false);
        versionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        panel.add(new JScrollPane(versionsArea), BorderLayout.CENTER);
        JButton refresh = new JButton("Обновить список");
        refresh.addActionListener(event -> showVersions());
        panel.add(refresh, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(resultArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Подробный журнал"));
        logPane.setEditable(false);
        logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(logPane), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton save = new JButton("Сохранить журнал…");
        save.addActionListener(event -> saveLog());
        JButton clear = new JButton("Очистить");
        clear.addActionListener(event -> clearLog());
        buttons.add(clear);
        buttons.add(save);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void addPathRow(JPanel form, GridBagConstraints c, int row, String label,
                            JTextField field, String buttonText, boolean directoryOnly) {
        JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
        rowPanel.add(field, BorderLayout.CENTER);
        JButton browse = new JButton(buttonText);
        browse.addActionListener(event -> choosePath(field, directoryOnly));
        rowPanel.add(browse, BorderLayout.EAST);
        addRow(form, c, row, label, rowPanel);
    }

    private void addRow(JPanel form, GridBagConstraints c, int row, String label, Component component) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.CENTER;
        form.add(component, c);
    }

    private JPanel withHint(JTextField field, String hint) {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(new JLabel(hint), BorderLayout.EAST);
        return panel;
    }

    private void choosePath(JTextField field, boolean directoryOnly) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directoryOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Выберите " + (directoryOnly ? "каталог" : "путь"));
        String current = field.getText().trim();
        if (!current.isEmpty()) {
            try {
                Path path = Path.of(current);
                if (Files.exists(path)) chooser.setSelectedFile(path.toFile());
            } catch (InvalidPathException e) {
                appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.WARN,
                        "Текущий путь некорректен, открылся начальный каталог выбора."));
            }
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void applyProfileDefaults() {
        Object selected = toField.getSelectedItem();
        if (selected == null) return;
        VersionProfile profile = VersionCatalog.find(selected.toString().trim());
        if (profile != null) {
            neoField.setText(profile.neoForge());
            javaField.setValue(profile.javaMajor());
        } else {
            neoField.setText("");
            javaField.setValue(21);
        }
    }

    private void loadProfiles() {
        try {
            versionsArea.setText(buildVersionsText());
        } catch (IOException e) {
            versionsArea.setText("Не удалось загрузить версии и правила: " + e.getMessage());
        }
    }

    private String buildVersionsText() throws IOException {
        StringBuilder text = new StringBuilder("Профили целевых версий:\n");
        for (VersionProfile profile : VersionCatalog.all()) {
            text.append("  ").append(profile.id()).append(" | Minecraft ").append(profile.minecraft())
                    .append(" | NeoForge ").append(profile.neoForge())
                    .append(" | Java ").append(profile.javaMajor()).append('\n');
        }
        text.append("\nВстроенные направленные маршруты:\n");
        for (String route : MigrationEngine.bundledRoutes()) text.append("  ").append(route).append('\n');
        text.append("\nНеизвестные версии можно указать вручную: для миграции обязательно задайте NeoForge.\n");
        return text.toString();
    }

    private void analyzeSelectedProject() {
        Path input = requiredPath(inputField, "исходный проект");
        if (input == null || busy) return;
        runWorker("Анализ проекта", (logger) -> {
            ProjectInfo info = ProjectAnalyzer.analyze(input, logger);
            return info.toReport();
        }, resultArea, false);
    }

    private void migrate() {
        if (busy) return;
        Path input = requiredPath(inputField, "исходный проект");
        if (input == null) return;
        String target = selectedTarget();
        if (target.isBlank()) {
            JOptionPane.showMessageDialog(frame, "Укажите целевую версию Minecraft.", "Не задана версия",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String neo = neoField.getText().trim();
        VersionProfile profile = VersionCatalog.find(target);
        if (neo.isBlank() && profile == null) {
            JOptionPane.showMessageDialog(frame, "Для неизвестной версии обязательно укажите NeoForge.", "Не задан NeoForge",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Path output = optionalPath(outputField);
            Path rules = optionalPath(rulesField);
            Path template = optionalPath(templateField);
            Integer java = (Integer) javaField.getValue();
            MigrationOptions options = new MigrationOptions(input, emptyToNull(fromField.getText()), target,
                    neo.isBlank() ? null : neo, java, output, rules, template,
                    dryRunField.isSelected(), forceField.isSelected(), skipBuildField.isSelected());
            runWorker("Миграция " + target, logger -> {
                MigrationReport report = MigrationEngine.migrate(options, logger);
                return report.toText();
            }, resultArea, true);
        } catch (RuntimeException e) {
            appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.ERROR,
                    "Некорректный путь или параметр: " + e.getMessage()));
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Некорректный параметр",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showVersions() {
        if (busy) return;
        runWorker("Загрузка профилей", logger -> {
            MigrationLog.log(logger, MigrationLog.Level.INFO, "Loading target version profiles.");
            String profileText = buildVersionsText();
            MigrationLog.log(logger, MigrationLog.Level.SUCCESS, "Version profiles and migration routes loaded.");
            return profileText;
        }, versionsArea, false);
    }

    private interface LoggedTask {
        String run(MigrationLog.Listener listener) throws Exception;
    }

    private void runWorker(String taskName, LoggedTask task, JTextArea destination, boolean migration) {
        setBusy(true, taskName + " выполняется…");
        new SwingWorker<String, MigrationLog.Event>() {
            @Override protected String doInBackground() throws Exception {
                return task.run(this::publish);
            }

            @Override protected void process(List<MigrationLog.Event> events) {
                events.forEach(MVSwitcherInterface.this::appendLog);
            }

            @Override protected void done() {
                try {
                    String result = get();
                    if (destination != null) destination.setText(result);
                    if (destination == resultArea) tabs.setSelectedIndex(3);
                    if (migration) {
                        appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.INFO,
                                "Migration report is available on the «Отчёт» tab."));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.ERROR,
                            taskName + " прервано."));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.ERROR,
                            taskName + " завершилось ошибкой: " + cause.getMessage()));
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Ошибка",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    setBusy(false, "Готово");
                }
            }
        }.execute();
    }

    private void appendLog(MigrationLog.Event event) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendLog(event));
            return;
        }
        String line = event + System.lineSeparator();
        savedLog.append(line);
        StyledDocument document = logPane.getStyledDocument();
        Style style = logPane.addStyle(event.level().name(), null);
        StyleConstants.setForeground(style, switch (event.level()) {
            case SUCCESS -> new Color(28, 120, 55);
            case WARN -> new Color(170, 105, 0);
            case ERROR -> new Color(175, 35, 35);
            case DEBUG -> Color.GRAY;
            case INFO -> Color.DARK_GRAY;
        });
        try {
            document.insertString(document.getLength(), line, style);
            logPane.setCaretPosition(document.getLength());
        } catch (BadLocationException e) {
            status.setText("Не удалось добавить строку в журнал: " + e.getMessage());
        }
    }

    private void saveLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранить журнал MVSwitcher");
        chooser.setSelectedFile(new java.io.File("mvswitcher.log"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(path, savedLog.toString(), StandardCharsets.UTF_8);
            appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.SUCCESS,
                    "Журнал сохранён: " + path));
        } catch (IOException e) {
            appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.ERROR,
                    "Не удалось сохранить журнал: " + e.getMessage()));
        }
    }

    private void clearLog() {
        savedLog.setLength(0);
        logPane.setText("");
        appendLog(new MigrationLog.Event(java.time.Instant.now(), MigrationLog.Level.INFO, "Журнал очищен."));
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        status.setText(message);
        progress.setIndeterminate(value);
        migrateButton.setEnabled(!value);
        analyzeButton.setEnabled(!value);
        versionsButton.setEnabled(!value);
        tabs.setCursor(value ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private Path requiredPath(JTextField field, String label) {
        String value = field.getText().trim();
        if (value.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Укажите " + label + ".", "Не указан путь",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            Path path = Path.of(value);
            if (!Files.exists(path)) {
                JOptionPane.showMessageDialog(frame, "Путь не найден: " + path, "Путь не найден",
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }
            return path;
        } catch (InvalidPathException e) {
            JOptionPane.showMessageDialog(frame, "Некорректный путь: " + e.getMessage(), "Путь некорректен",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private Path optionalPath(JTextField field) {
        return field.getText().isBlank() ? null : Path.of(field.getText().trim());
    }

    private String selectedTarget() {
        Object selected = toField.getEditor().getItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private static String emptyToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
