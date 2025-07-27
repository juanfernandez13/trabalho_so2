import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random; // Importa Processo e Recurso (apenas para MainFrame, se forem classes internas completas)
import java.util.concurrent.Semaphore;
import java.util.Arrays;
import java.util.function.Consumer;

// ----------------------------------------------------------------------
// IMPORTANTE: Todas as classes a seguir (Recurso, Processo, SistemaOperacional)
// e as classes de UI auxiliares (PainelRecursos, PainelProcessos, PainelLog,
// ConfiguracaoRecursosDialog, AdicionarProcessoDialog) DEVEM SER COLOCADAS
// EM ARQUIVOS SEPARADOS NO MESMO DIRETÓRIO (src) ou COMO CLASSES INTERNAS
// DENTRO DE MainFrame, conforme esta versão.
// ----------------------------------------------------------------------

// Se você manter Recurso, Processo, SistemaOperacional em arquivos separados
// (que é o ideal, mesmo que estejam no mesmo diretório src),
// VOCÊ PRECISA COPIAR O CONTEÚDO DESSES ARQUIVOS ABAIXO E COLÁ-LOS EM
// SEUS RESPECTIVOS ARQUIVOS "Recurso.java", "Processo.java" e "SistemaOperacional.java".

// Caso contrário, se você quiser TUDO em um único MainFrame.java
// (não recomendado para organização, mas possível), então essas classes
// também deveriam ser aninhadas aqui, o que tornaria o arquivo GIGANTE.
// Para simplicidade e seguir a intenção da questão anterior, vamos manter
// elas como arquivos separados sem declaração de pacote.

// As classes auxiliares de UI (PainelRecursos, etc.) ESTÃO ANINHADAS aqui.

public class MainFrame extends JFrame {

    private Map<Integer, Recurso> recursos = new ConcurrentHashMap<>();
    private Map<Integer, Processo> processos = new ConcurrentHashMap<>();
    private SistemaOperacional sistemaOperacional;

    private PainelRecursos painelRecursos;
    private PainelProcessos painelProcessos;
    private PainelLog painelLog;
    private JLabel statusDeadlockLabel;
    private JButton btnIniciarSimulacao;
    private JButton btnAdicionarProcesso;
    private JButton btnRemoverProcesso;
    private JButton btnConfigurarRecursos;

    public MainFrame() {
        setTitle("Simulação de Deadlock");
        setSize(1000, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        initComponents();
        createLayout();
        addWindowCloseListener();

        // Desabilita botões que dependem da configuração de recursos inicialmente
        btnIniciarSimulacao.setEnabled(false);
        btnAdicionarProcesso.setEnabled(false);
    }

    private void initComponents() {
        painelRecursos = new PainelRecursos();
        painelProcessos = new PainelProcessos();
        painelLog = new PainelLog();

        statusDeadlockLabel = new JLabel("Status Deadlock: N/A", SwingConstants.CENTER);
        statusDeadlockLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        statusDeadlockLabel.setForeground(Color.BLUE);

        btnConfigurarRecursos = new JButton("Configurar Recursos");
        btnConfigurarRecursos.addActionListener(e -> configurarRecursos());

        btnIniciarSimulacao = new JButton("Iniciar Simulação");
        btnIniciarSimulacao.addActionListener(e -> iniciarSimulacao());

        btnAdicionarProcesso = new JButton("Adicionar Processo");
        btnAdicionarProcesso.addActionListener(e -> adicionarProcesso());

        btnRemoverProcesso = new JButton("Remover Processo");
        btnRemoverProcesso.addActionListener(e -> removerProcesso());
        btnRemoverProcesso.setEnabled(false);
    }

    private void createLayout() {
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.setBorder(new EmptyBorder(10, 10, 0, 10));
        northPanel.add(statusDeadlockLabel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        buttonPanel.add(btnConfigurarRecursos);
        buttonPanel.add(btnIniciarSimulacao);
        buttonPanel.add(btnAdicionarProcesso);
        buttonPanel.add(btnRemoverProcesso);
        northPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);

        JSplitPane centerSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        centerSplitPane.setResizeWeight(0.3);
        centerSplitPane.setLeftComponent(painelRecursos);
        centerSplitPane.setRightComponent(painelProcessos);
        centerSplitPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setResizeWeight(0.7);
        mainSplitPane.setTopComponent(centerSplitPane);
        mainSplitPane.setBottomComponent(new JScrollPane(painelLog));
        mainSplitPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        add(mainSplitPane, BorderLayout.CENTER);
    }

    private void addWindowCloseListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (sistemaOperacional != null) {
                    sistemaOperacional.encerrar();
                }
                for (Processo p : processos.values()) {
                    p.encerrar();
                }
                try {
                    Thread.sleep(100); // Pequeno delay para threads encerrarem
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void configurarRecursos() {
        ConfiguracaoRecursosDialog dialog = new ConfiguracaoRecursosDialog(this, recursos);
        dialog.setVisible(true);
        if (dialog.isConfigured()) {
            recursos.clear(); // Limpa os recursos anteriores
            recursos.putAll(dialog.getRecursosConfigurados()); // Adiciona os novos
            painelRecursos.atualizarRecursos(recursos.values());
            btnIniciarSimulacao.setEnabled(true);
            btnAdicionarProcesso.setEnabled(true);
            log("Recursos configurados com sucesso.");
        }
    }

    private void iniciarSimulacao() {
        if (sistemaOperacional != null && sistemaOperacional.isAlive()) {
            JOptionPane.showMessageDialog(this, "A simulação já está em execução.", "Aviso", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String input = JOptionPane.showInputDialog(this, "Informe o intervalo (em segundos) para o SO verificar deadlocks:", "Intervalo SO", JOptionPane.PLAIN_MESSAGE);
        if (input != null && !input.trim().isEmpty()) {
            try {
                long deltaT = Long.parseLong(input);
                if (deltaT <= 0) {
                    throw new NumberFormatException("O intervalo deve ser um número positivo.");
                }

                sistemaOperacional = new SistemaOperacional(deltaT, recursos, processos, this::log, this::updateDeadlockStatus);
                sistemaOperacional.start();
                log("Simulação iniciada. SO verificando a cada " + deltaT + " segundos.");
                btnIniciarSimulacao.setEnabled(false);
                btnConfigurarRecursos.setEnabled(false);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Intervalo inválido. Por favor, insira um número inteiro positivo.", "Erro", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void adicionarProcesso() {
        if (recursos.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Por favor, configure os recursos antes de adicionar processos.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AdicionarProcessoDialog dialog = new AdicionarProcessoDialog(this);
        dialog.setVisible(true);

        if (dialog.isProcessAdded()) {
            int id = dialog.getProcessId();
            long deltaTs = dialog.getDeltaTs();
            long deltaTu = dialog.getDeltaTu();

            if (processos.containsKey(id)) {
                JOptionPane.showMessageDialog(this, "Já existe um processo com o ID " + id + ".", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (processos.size() >= 10) {
                JOptionPane.showMessageDialog(this, "Número máximo de 10 processos atingido.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Processo novoProcesso = new Processo(id, deltaTs, deltaTu, recursos, this::log, this::updateUI);
            processos.put(id, novoProcesso);
            novoProcesso.start();
            log("Processo P" + id + " criado e iniciado (Ts: " + deltaTs + "s, Tu: " + deltaTu + "s).");
            updateUI(null); // Força atualização da UI para mostrar o novo processo
            btnRemoverProcesso.setEnabled(true);
        }
    }

    private void removerProcesso() {
        if (processos.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Não há processos para remover.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String input = JOptionPane.showInputDialog(this, "Informe o ID do processo a ser removido:", "Remover Processo", JOptionPane.PLAIN_MESSAGE);
        if (input != null && !input.trim().isEmpty()) {
            try {
                int id = Integer.parseInt(input);
                Processo processoARemover = processos.get(id);
                if (processoARemover != null) {
                    processoARemover.encerrar();
                    // Pequeno delay para a thread do processo terminar
                    new Thread(() -> {
                        try {
                            processoARemover.join(1000); // Espera até 1 segundo
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            SwingUtilities.invokeLater(() -> {
                                processos.remove(id); // Remove o processo do mapa
                                log("Processo P" + id + " removido.");
                                updateUI(null);
                                if (processos.isEmpty()) {
                                    btnRemoverProcesso.setEnabled(false);
                                }
                            });
                        }
                    }).start();

                } else {
                    JOptionPane.showMessageDialog(this, "Processo com ID " + id + " não encontrado.", "Erro", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "ID inválido. Por favor, insira um número inteiro.", "Erro", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Método para adicionar mensagens ao Painel de Log
    private void log(String message) {
        SwingUtilities.invokeLater(() -> painelLog.addLog(message));
    }

    // Método para atualizar a UI dos painéis de recursos e processos
    private void updateUI(Runnable callback) {
        SwingUtilities.invokeLater(() -> {
            painelRecursos.atualizarRecursos(recursos.values());
            painelProcessos.atualizarProcessos(processos.values());
            if (callback != null) {
                callback.run();
            }
        });
    }

    // Método para atualizar o status do deadlock na label principal
    private void updateDeadlockStatus(List<Integer> deadlockedProcessIds) {
        SwingUtilities.invokeLater(() -> {
            if (deadlockedProcessIds != null && !deadlockedProcessIds.isEmpty()) {
                statusDeadlockLabel.setText("Status Deadlock: DEADLOCK DETECTADO! Processos: " + deadlockedProcessIds);
                statusDeadlockLabel.setForeground(Color.RED);
            } else {
                statusDeadlockLabel.setText("Status Deadlock: Nenhum deadlock detectado.");
                statusDeadlockLabel.setForeground(Color.BLUE);
            }
            // Força a atualização dos painéis para refletir o status de deadlock nos processos
            painelProcessos.setDeadlockedProcesses(deadlockedProcessIds);
            painelProcessos.atualizarProcessos(processos.values());
        });
    }


    // Classe Interna: Diálogo para Configuração de Recursos
    private class ConfiguracaoRecursosDialog extends JDialog {

        private DefaultTableModel tableModel;
        private JTable recursoTable;
        private Map<Integer, Recurso> recursosConfigurados;
        private boolean configured = false;

        public ConfiguracaoRecursosDialog(JFrame parent, Map<Integer, Recurso> currentResources) {
            super(parent, "Configurar Recursos do Sistema", true);
            setSize(700, 400);
            setLocationRelativeTo(parent);
            setLayout(new BorderLayout(10, 10));
            ((JPanel)getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

            recursosConfigurados = new HashMap<>(currentResources); // Copia os recursos existentes

            initComponents();
            populateTable();
        }

        private void initComponents() {
            // Configuração da tabela
            String[] columnNames = {"Nome do Recurso", "ID", "Quantidade Instâncias"};
            tableModel = new DefaultTableModel(columnNames, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return true; // Todas as células são editáveis
                }
            };
            recursoTable = new JTable(tableModel);
            JScrollPane scrollPane = new JScrollPane(recursoTable);
            add(scrollPane, BorderLayout.CENTER);

            // Painel de botões
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); // <-- Este painel
            JButton addRowButton = new JButton("Adicionar Recurso");
            addRowButton.addActionListener(e -> addEmptyRow());
            buttonPanel.add(addRowButton);

            JButton deleteRowButton = new JButton("Remover Recurso Selecionado");
            deleteRowButton.addActionListener(e -> removeSelectedRow());
            buttonPanel.add(deleteRowButton);

            // GARANTA QUE ESTE BOTÃO ESTEJA AQUI:
            JButton saveButton = new JButton("Salvar Configuração");
            saveButton.addActionListener(e -> saveConfiguration());
            buttonPanel.add(saveButton); // <-- Adicionar o botão ao painel

            add(buttonPanel, BorderLayout.SOUTH);
        }

        // Preenche a tabela com os recursos atuais
        private void populateTable() {
            for (Recurso recurso : recursosConfigurados.values()) {
                tableModel.addRow(new Object[]{recurso.getNome(), recurso.getId(), recurso.getTotalInstancias()});
            }
        }

        // Adiciona uma nova linha vazia na tabela
        private void addEmptyRow() {
            if (tableModel.getRowCount() >= 10) {
                JOptionPane.showMessageDialog(this, "Número máximo de 10 tipos de recursos atingido.", "Limite de Recursos", JOptionPane.WARNING_MESSAGE);
                return;
            }
            tableModel.addRow(new Object[]{"", "", ""});
        }

        // Remove a linha selecionada da tabela
        private void removeSelectedRow() {
            int selectedRow = recursoTable.getSelectedRow();
            if (selectedRow != -1) {
                tableModel.removeRow(selectedRow);
            } else {
                JOptionPane.showMessageDialog(this, "Selecione uma linha para remover.", "Aviso", JOptionPane.WARNING_MESSAGE);
            }
        }

        // Salva a configuração dos recursos da tabela
        private void saveConfiguration() {
            Map<Integer, Recurso> tempRecursos = new HashMap<>();
            try {
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    String nome = tableModel.getValueAt(i, 0).toString().trim();
                    int id = Integer.parseInt(tableModel.getValueAt(i, 1).toString());
                    int quantidade = Integer.parseInt(tableModel.getValueAt(i, 2).toString());

                    if (nome.isEmpty()) {
                        throw new IllegalArgumentException("Nome do recurso não pode ser vazio na linha " + (i + 1));
                    }
                    if (id <= 0) {
                        throw new IllegalArgumentException("ID do recurso deve ser um número positivo na linha " + (i + 1));
                    }
                    if (quantidade <= 0) {
                        throw new IllegalArgumentException("Quantidade de instâncias deve ser um número positivo na linha " + (i + 1));
                    }
                    if (tempRecursos.containsKey(id)) {
                        throw new IllegalArgumentException("ID de recurso duplicado: " + id + " na linha " + (i + 1));
                    }

                    tempRecursos.put(id, new Recurso(nome, id, quantidade));
                }
                recursosConfigurados = tempRecursos; // Atualiza o mapa de recursos configurados
                configured = true; // Marca como configurado com sucesso
                dispose(); // Fecha o diálogo
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Erro de formato numérico. Verifique os campos ID e Quantidade.", "Erro de Entrada", JOptionPane.ERROR_MESSAGE);
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Erro de Configuração", JOptionPane.ERROR_MESSAGE);
            }
        }

        public Map<Integer, Recurso> getRecursosConfigurados() {
            return recursosConfigurados;
        }

        public boolean isConfigured() {
            return configured;
        }
    }

    // Classe Interna: Painel para exibir o Status dos Processos
    private class PainelProcessos extends JPanel {

        private JPanel processosContainer;
        private List<Integer> deadlockedProcesses = new ArrayList<>();

        public PainelProcessos() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Status dos Processos", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 14)));

            processosContainer = new JPanel();
            processosContainer.setLayout(new BoxLayout(processosContainer, BoxLayout.Y_AXIS));
            JScrollPane scrollPane = new JScrollPane(processosContainer);
            add(scrollPane, BorderLayout.CENTER);
        }

        public void setDeadlockedProcesses(List<Integer> deadlockedProcesses) {
            this.deadlockedProcesses = deadlockedProcesses;
        }

        public void atualizarProcessos(Collection<Processo> processos) {
            processosContainer.removeAll(); // Limpa os componentes existentes
            if (processos.isEmpty()) {
                processosContainer.add(new JLabel("Nenhum processo em execução."));
            } else {
                for (Processo processo : processos) {
                    JPanel processoPanel = new JPanel();
                    processoPanel.setLayout(new BoxLayout(processoPanel, BoxLayout.Y_AXIS));
                    processoPanel.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                            BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    ));

                    // Destaca processos em deadlock
                    boolean isDeadlocked = deadlockedProcesses.contains(processo.getProcessId());
                    processoPanel.setBackground(isDeadlocked ? new Color(255, 200, 200) : UIManager.getColor("Panel.background"));

                    JLabel idLabel = new JLabel("Processo ID: " + processo.getProcessId() + " - Status: " + processo.getStatus());
                    idLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    processoPanel.add(idLabel);

                    if (processo.getStatus() == Processo.Status.BLOQUEADO && processo.getRecursoAguardando() != null) {
                        processoPanel.add(new JLabel("Aguardando: " + processo.getRecursoAguardando().getNome()));
                    }

                    if (!processo.getRecursosAlocados().isEmpty()) {
                        StringBuilder alocados = new StringBuilder("Alocados: ");
                        for (Map.Entry<Integer, Integer> entry : processo.getRecursosAlocados().entrySet()) {
                            // Poderia buscar o nome do recurso do mapa "recursos" da MainFrame se necessário
                            alocados.append("R").append(entry.getKey()).append(" (x").append(entry.getValue()).append(") ");
                        }
                        processoPanel.add(new JLabel(alocados.toString().trim()));
                    } else if (processo.getStatus() != Processo.Status.BLOQUEADO) {
                        processoPanel.add(new JLabel("Alocados: Nenhum"));
                    }

                    processosContainer.add(processoPanel);
                    processosContainer.add(Box.createRigidArea(new Dimension(0, 5))); // Espaçador
                }
            }
            revalidate();
            repaint();
        }
    }

    // Classe Interna: Painel para exibir os Recursos do Sistema
    private class PainelRecursos extends JPanel {

        private JPanel recursosContainer;

        public PainelRecursos() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Recursos do Sistema", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 14)));

            recursosContainer = new JPanel();
            recursosContainer.setLayout(new BoxLayout(recursosContainer, BoxLayout.Y_AXIS));
            JScrollPane scrollPane = new JScrollPane(recursosContainer);
            add(scrollPane, BorderLayout.CENTER);
        }

        public void atualizarRecursos(Collection<Recurso> recursos) {
            recursosContainer.removeAll(); // Limpa os componentes existentes
            if (recursos.isEmpty()) {
                recursosContainer.add(new JLabel("Nenhum recurso configurado."));
            } else {
                for (Recurso recurso : recursos) {
                    JLabel recursoLabel = new JLabel(recurso.toString());
                    recursoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                    recursosContainer.add(recursoLabel);
                }
            }
            revalidate();
            repaint();
        }
    }

    // Classe Interna: Painel para exibir o Log de Operações
    private class PainelLog extends JPanel {

        private JTextArea logArea;
        private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

        public PainelLog() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Log de Operações", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 14)));

            logArea = new JTextArea();
            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(logArea);
            add(scrollPane, BorderLayout.CENTER);
        }

        public void addLog(String message) {
            String timestamp = sdf.format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength()); // Rola automaticamente para o final
        }
    }

    // Classe Interna: Diálogo para Adicionar Novo Processo
    private class AdicionarProcessoDialog extends JDialog {
        private JTextField idField;
        private JTextField deltaTsField;
        private JTextField deltaTuField;
        private boolean processAdded = false;

        public AdicionarProcessoDialog(JFrame parent) {
            super(parent, "Adicionar Novo Processo", true);
            setSize(300, 200);
            setLocationRelativeTo(parent);
            setLayout(new GridLayout(4, 2, 10, 10));
            ((JPanel)getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

            add(new JLabel("ID do Processo:"));
            idField = new JTextField();
            add(idField);

            add(new JLabel("Ts (s):"));
            deltaTsField = new JTextField();
            add(deltaTsField);

            add(new JLabel("Tu (s):"));
            deltaTuField = new JTextField();
            add(deltaTuField);

            JButton addButton = new JButton("Adicionar");
            addButton.addActionListener(e -> {
                try {
                    int id = Integer.parseInt(idField.getText());
                    long deltaTs = Long.parseLong(deltaTsField.getText());
                    long deltaTu = Long.parseLong(deltaTuField.getText());

                    if (id <= 0 || deltaTs <= 0 || deltaTu <= 0) {
                        JOptionPane.showMessageDialog(this, "Todos os valores devem ser inteiros positivos.", "Erro de Entrada", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    // A validação de ID duplicado e limite de processos é feita no MainFrame.adicionarProcesso()
                    processAdded = true;
                    dispose();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Por favor, insira valores numéricos válidos para os campos de tempo e ID.", "Erro de Entrada", JOptionPane.ERROR_MESSAGE);
                }
            });
            add(addButton);

            JButton cancelButton = new JButton("Cancelar");
            cancelButton.addActionListener(e -> dispose());
            add(cancelButton);
        }

        public boolean isProcessAdded() {
            return processAdded;
        }

        public int getProcessId() {
            return Integer.parseInt(idField.getText());
        }

        public long getDeltaTs() {
            return Long.parseLong(deltaTsField.getText());
        }

        public long getDeltaTu() {
            return Long.parseLong(deltaTuField.getText());
        }
    }
}