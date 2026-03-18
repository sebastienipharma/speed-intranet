package com.speedintranet;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class SpeedIntranetGui {

    private JFrame frame;
    private JComboBox<String> modeCombo;
    private JTextField serverField;
    private JTextField portField;
    private JTextField configField;
    private JComboBox<String> testsCombo;
    private JComboBox<String> directionCombo;
    private JSpinner repeatSpinner;
    private JSpinner timeoutSpinner;
    private JTextField outputField;
    private JTextArea logArea;
    private JButton runButton;
    private JButton stopButton;
    private volatile Thread runThread;

    private PrintWriter logWriter;
    private File logFile;
    private JLabel logFileLabel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                }
                new SpeedIntranetGui().createAndShow();
            }
        });
    }

    private void createAndShow() {
        frame = new JFrame("speed-intranet v1.05");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(780, 620);
        frame.setLocationRelativeTo(null);

        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(10, 10, 10, 10));

        main.add(buildConfigPanel(), BorderLayout.NORTH);
        main.add(buildLogPanel(), BorderLayout.CENTER);
        main.add(buildButtonPanel(), BorderLayout.SOUTH);

        frame.setContentPane(main);
        frame.setVisible(true);

        initLogFile();
        redirectSystemOut();
        updateFieldVisibility();
    }

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Mode
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Mode :"), gbc);
        modeCombo = new JComboBox<String>(new String[]{"server", "client", "auto"});
        modeCombo.setSelectedItem("client");
        modeCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateFieldVisibility();
            }
        });
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(modeCombo, gbc);

        // Server IP
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Serveur IP :"), gbc);
        serverField = new JTextField("192.168.1.2", 20);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(serverField, gbc);

        // Port
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Port :"), gbc);
        portField = new JTextField("5201", 8);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(portField, gbc);

        // Config file
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Config :"), gbc);
        configField = new JTextField("config.ini", 20);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(configField, gbc);

        // Tests
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Tests :"), gbc);
        testsCombo = new JComboBox<String>(new String[]{"all", "small", "medium", "large", "small,medium"});
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(testsCombo, gbc);

        // Direction
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Direction :"), gbc);
        directionCombo = new JComboBox<String>(new String[]{"both", "upload", "download"});
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(directionCombo, gbc);

        // Repeat
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Répétitions :"), gbc);
        repeatSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(repeatSpinner, gbc);

        // Timeout
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Timeout (s) :"), gbc);
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 300, 1));
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(timeoutSpinner, gbc);

        // Output
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Export :"), gbc);
        outputField = new JTextField("", 20);
        outputField.setToolTipText("Laisser vide ou indiquer un fichier .json ou .csv");
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(outputField, gbc);

        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Résultats"));
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        panel.add(scroll, BorderLayout.CENTER);

        logFileLabel = new JLabel(" ");
        logFileLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        logFileLabel.setForeground(Color.DARK_GRAY);
        panel.add(logFileLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));

        runButton = new JButton("Lancer");
        runButton.setPreferredSize(new Dimension(140, 32));
        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startTest();
            }
        });
        panel.add(runButton);

        stopButton = new JButton("Arrêter");
        stopButton.setPreferredSize(new Dimension(140, 32));
        stopButton.setEnabled(false);
        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopTest();
            }
        });
        panel.add(stopButton);

        JButton clearButton = new JButton("Effacer");
        clearButton.setPreferredSize(new Dimension(100, 32));
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logArea.setText("");
            }
        });
        panel.add(clearButton);

        JButton openLogButton = new JButton("Ouvrir log");
        openLogButton.setPreferredSize(new Dimension(110, 32));
        openLogButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openLogFile();
            }
        });
        panel.add(openLogButton);

        return panel;
    }

    private void updateFieldVisibility() {
        String mode = (String) modeCombo.getSelectedItem();
        boolean isClient = "client".equals(mode);
        boolean isServer = "server".equals(mode);
        boolean isAuto = "auto".equals(mode);

        serverField.setEnabled(isClient);
        configField.setEnabled(isAuto);
        testsCombo.setEnabled(!isServer);
        directionCombo.setEnabled(!isServer);
        repeatSpinner.setEnabled(!isServer);
        outputField.setEnabled(!isServer);
    }

    private void startTest() {
        logArea.setText("");
        runButton.setEnabled(false);
        stopButton.setEnabled(true);

        final String[] args = buildArgs();

        // Nouveau fichier de log pour ce test
        initLogFile();

        // Logguer les paramètres utilisés
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        System.out.println("=== Démarrage du test : " + timestamp + " ===");
        System.out.println("Paramètres : " + java.util.Arrays.toString(args));
        System.out.println("--------------------------------------------");

        runThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SpeedIntranet.main(args);
                } catch (Exception ex) {
                    System.out.println("[ERROR] " + ex.getMessage());
                    ex.printStackTrace(System.err);
                } finally {
                    System.out.println("--------------------------------------------");
                    System.out.println("=== Fin du test : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ===");
                    if (logWriter != null) {
                        logWriter.flush();
                    }
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            runButton.setEnabled(true);
                            stopButton.setEnabled(false);
                        }
                    });
                }
            }
        }, "speed-worker");
        runThread.setDaemon(true);
        runThread.start();
    }

    private void stopTest() {
        Thread t = runThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            System.out.println("[INFO] Arrêt demandé.");
        }
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private String[] buildArgs() {
        String mode = (String) modeCombo.getSelectedItem();
        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add(mode);
        args.add("--port");
        args.add(portField.getText().trim());
        args.add("--timeout");
        args.add(timeoutSpinner.getValue().toString());

        if ("client".equals(mode)) {
            args.add("--server");
            args.add(serverField.getText().trim());
            args.add("--tests");
            args.add((String) testsCombo.getSelectedItem());
            args.add("--direction");
            args.add((String) directionCombo.getSelectedItem());
            args.add("--repeat");
            args.add(repeatSpinner.getValue().toString());
            String out = outputField.getText().trim();
            if (!out.isEmpty()) {
                args.add("--output");
                args.add(out);
            }
        } else if ("auto".equals(mode)) {
            args.add("--config");
            args.add(configField.getText().trim());
            args.add("--tests");
            args.add((String) testsCombo.getSelectedItem());
            args.add("--direction");
            args.add((String) directionCombo.getSelectedItem());
            args.add("--repeat");
            args.add(repeatSpinner.getValue().toString());
            String out = outputField.getText().trim();
            if (!out.isEmpty()) {
                args.add("--output");
                args.add(out);
            }
        }

        return args.toArray(new String[0]);
    }

    private void initLogFile() {
        try {
            if (logWriter != null) {
                logWriter.close();
            }
            String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            logFile = new File("speedtest-" + ts + ".log");
            logWriter = new PrintWriter(new FileOutputStream(logFile, true), true);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    logFileLabel.setText("Log : " + logFile.getAbsolutePath());
                }
            });
        } catch (IOException e) {
            logWriter = null;
        }
    }

    private void openLogFile() {
        if (logFile != null && logFile.exists()) {
            try {
                Desktop.getDesktop().open(logFile);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame,
                    "Impossible d'ouvrir : " + logFile.getAbsolutePath(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(frame,
                "Aucun fichier de log disponible.",
                "Information", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void redirectSystemOut() {
        PrintStream guiOut = new PrintStream(new OutputStream() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void write(int b) {
                char c = (char) b;
                buffer.append(c);
                if (c == '\n') {
                    final String line = buffer.toString();
                    buffer.setLength(0);
                    // Écriture dans le fichier de log
                    if (logWriter != null) {
                        logWriter.print(line);
                        logWriter.flush();
                    }
                    // Affichage dans la GUI
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            logArea.append(line);
                            logArea.setCaretPosition(logArea.getDocument().getLength());
                        }
                    });
                }
            }
        }, true);
        System.setOut(guiOut);
        System.setErr(guiOut);
    }
}




