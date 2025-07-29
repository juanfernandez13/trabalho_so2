import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Representa um processo do sistema que compete por recursos.
 * A lógica desta classe é projetada para simular um comportamento realista onde
 * um processo pode precisar de múltiplos recursos para realizar uma "tarefa",
 * criando a condição de "Posse e Espera" e, consequentemente, a possibilidade de deadlock.
 */
public class Processo extends Thread {

    /**
     * Enum para representar os possíveis estados de um processo,
     * facilitando a visualização na interface gráfica.
     */
    public enum Status {
        RODANDO,   // O processo está executando ou em espera ativa (sleep).
        BLOQUEADO, // O processo está parado, aguardando por um recurso.
        ENCERRADO  // O processo terminou sua execução.
    }

    // Atributos essenciais do processo
    private final int id;
    private final long deltaTs; // Intervalo de tempo entre solicitações de recursos.
    private final long deltaTu; // Tempo que o processo leva para utilizar os recursos.

    // Estruturas de dados para gerenciamento de recursos
    private final Map<Integer, Recurso> recursosDisponiveis; // Referência a todos os recursos do sistema.
    private final Map<Integer, Integer> recursosAlocados;    // Mapeia ID do recurso -> Quantidade alocada por ESTE processo.
    private Recurso recursoAguardando;                       // Qual recurso o processo está esperando neste momento.

    // Atributos de controle e comunicação com a UI
    private Status status;
    private final Random random;
    private final Consumer<String> logConsumer;              // Callback para enviar logs para a interface.
    private final Consumer<Runnable> uiUpdateCallback;       // Callback para solicitar atualização da interface.
    private volatile boolean running = true;                 // Flag para controlar o loop principal da thread.
    private final Semaphore simulationStartSemaphore;        // Semáforo para sincronizar o início dos processos.

    /**
     * Construtor da classe Processo.
     */
    public Processo(int id, long deltaTs, long deltaTu, Map<Integer, Recurso> recursosDisponiveis, Consumer<String> logConsumer, Consumer<Runnable> uiUpdateCallback, Semaphore simulationStartSemaphore) {
        this.id = id;
        this.deltaTs = deltaTs;
        this.deltaTu = deltaTu;
        this.recursosDisponiveis = recursosDisponiveis;
        this.recursosAlocados = new ConcurrentHashMap<>();
        this.status = Status.RODANDO;
        this.random = new Random();
        this.logConsumer = logConsumer;
        this.uiUpdateCallback = uiUpdateCallback;
        this.simulationStartSemaphore = simulationStartSemaphore;
    }

    // --- Getters públicos para a UI e o Sistema Operacional ---
    public int getProcessId() { return id; }
    public Status getStatus() { return status; }
    public Recurso getRecursoAguardando() { return recursoAguardando; }
    public Map<Integer, Integer> getRecursosAlocados() { return recursosAlocados; }
    
    /**
     * Sinaliza para a thread encerrar sua execução de forma segura.
     * Interrompe a thread para acordá-la de qualquer estado de espera (sleep/acquire).
     */
    public void encerrar() {
        this.running = false;
        this.interrupt();
        log("Processo " + id + " recebendo sinal para encerrar.");
    }

    // --- Métodos utilitários privados ---
    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept("P" + id + ": " + message);
        }
    }

    private void updateUI() {
        if (uiUpdateCallback != null) {
            uiUpdateCallback.accept(() -> {});
        }
    }

    /**
     * Método principal da thread, que define o ciclo de vida do processo.
     */
    @Override
    public void run() {
        // Bloco inicial: aguarda o sinal para começar a simulação.
        try {
            log("Aguardando início da simulação...");
            status = Status.BLOQUEADO;
            updateUI();
            simulationStartSemaphore.acquire();
            log("Simulação iniciada para este processo.");
            status = Status.RODANDO;
            updateUI();
        } catch (InterruptedException e) {
            log("Interrompido antes do início. Encerrando.");
            Thread.currentThread().interrupt();
            status = Status.ENCERRADO;
            updateUI();
            return;
        }

        // Loop principal: o processo executa "tarefas" enquanto a flag 'running' for verdadeira.
        while(running) {
            // 1. Define uma nova tarefa: decide quais e quantos recursos vai precisar.
            List<Recurso> recursosDesejados = escolherRecursosParaTarefa();
            if (recursosDesejados.isEmpty()) {
                log("Não há recursos suficientes no sistema para uma tarefa. Aguardando...");
                try {
                    Thread.sleep(deltaTs * 1000);
                    continue; // Pula para a próxima iteração do loop.
                } catch (InterruptedException e) {
                    running = false;
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log("Nova tarefa: Precisa de " + recursosDesejados.size() + " recursos: " + recursosDesejados.stream().map(Recurso::getNome).collect(Collectors.toList()));

            boolean tarefaConcluida = true;
            try {
                // 2. Tenta adquirir todos os recursos para a tarefa, um por um.
                //    É aqui que a condição de "Posse e Espera" é criada.
                for (Recurso recurso : recursosDesejados) {
                    log("Solicitando recurso: " + recurso.getNome());
                    recursoAguardando = recurso;
                    status = Status.BLOQUEADO;
                    updateUI();

                    // Adquire o recurso. A thread bloqueia aqui se o recurso não estiver disponível.
                    recurso.alocarInstancia();

                    // Se a execução chegou aqui, o recurso foi alocado com sucesso.
                    recursosAlocados.put(recurso.getId(), 1);
                    recursoAguardando = null;
                    status = Status.RODANDO;
                    log("Alocou o recurso " + recurso.getNome() + ". Recursos atuais: " + recursosAlocados.keySet());
                    updateUI();
                    
                    // Conforme o requisito, espera deltaTs antes de solicitar o próximo recurso da tarefa.
                    if (recursosDesejados.indexOf(recurso) < recursosDesejados.size() - 1) {
                         Thread.sleep(deltaTs * 1000);
                    }
                }

                // 3. Se adquiriu todos, utiliza os recursos pelo tempo deltaTu.
                log("Adquiriu todos os recursos necessários. Utilizando por " + deltaTu + "s.");
                Thread.sleep(deltaTu * 1000);

            } catch (InterruptedException e) {
                log("Foi interrompido durante a tarefa.");
                tarefaConcluida = false;
                running = false; // Sinaliza para o loop principal parar.
                Thread.currentThread().interrupt(); // Preserva o status de interrupção.
            } finally {
                // 4. Garante que todos os recursos alocados sejam liberados no final da tarefa.
                if (!recursosAlocados.isEmpty()) {
                    log("Tarefa finalizada (" + (tarefaConcluida ? "sucesso" : "interrupção") + "). Liberando todos os recursos.");
                    liberarTodosRecursos();
                }
            }

            // 5. Pausa antes de iniciar um novo ciclo de tarefa.
             try {
                if(running) {
                   log("Aguardando para iniciar nova tarefa...");
                   Thread.sleep(deltaTs * 1000);
                }
            } catch (InterruptedException e) {
                running = false;
                Thread.currentThread().interrupt();
            }
        }

        status = Status.ENCERRADO;
        log("Ciclo de vida encerrado.");
        updateUI();
    }
    
    /**
     * Seleciona uma quantidade aleatória (entre 2 e 3) de recursos aleatórios
     * que o processo precisará para a sua próxima "tarefa".
     * Este método é a chave para criar cenários de deadlock dinâmicos e imprevisíveis.
     */
    private List<Recurso> escolherRecursosParaTarefa() {
        List<Recurso> todosRecursos = new ArrayList<>(recursosDisponiveis.values());
        int totalTiposRecurso = todosRecursos.size();

        if (totalTiposRecurso < 2) {
            // Não é possível criar um cenário de 'posse e espera' com menos de 2 tipos de recurso.
            return Collections.emptyList();
        }

        // Define que um processo pode pedir entre 2 e um máximo de 3 recursos por tarefa,
        // limitado pela quantidade de tipos de recurso existentes.
        int maxRecursosParaPedir = Math.min(totalTiposRecurso, 3);
        int quantidadeAPedir = 2 + (maxRecursosParaPedir > 2 ? random.nextInt(maxRecursosParaPedir - 1) : 0);

        // Embaralha a lista de todos os recursos para pegar uma seleção aleatória.
        Collections.shuffle(todosRecursos, random);

        // Retorna uma sublista com os recursos escolhidos para esta tarefa.
        return todosRecursos.subList(0, quantidadeAPedir);
    }
    
    /**
     * Libera todas as instâncias de recursos que este processo alocou.
     */
    private void liberarTodosRecursos() {
        if (recursosAlocados.isEmpty()) return;
        
        // Itera sobre uma cópia das chaves para evitar ConcurrentModificationException.
        for (Integer recursoId : new ArrayList<>(recursosAlocados.keySet())) {
            Recurso recurso = recursosDisponiveis.get(recursoId);
            if (recurso != null) {
                // Como cada recurso só tem 1 instância alocada por este processo, libera uma vez.
                recurso.liberarInstancia();
            }
        }
        recursosAlocados.clear();
        updateUI();
    }
}
