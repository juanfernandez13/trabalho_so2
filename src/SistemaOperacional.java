import java.util.*;
import java.util.function.Consumer;

public class SistemaOperacional extends Thread {
    private long deltaT; // Intervalo de verificação de deadlock (em segundos)
    private Map<Integer, Recurso> recursos; // Todos os recursos registrados no sistema
    private Map<Integer, Processo> processos; // Todos os processos ativos no sistema
    private Consumer<String> logConsumer;              // Callback para registrar mensagens na UI
    private Consumer<List<Integer>> deadlockDetectedCallback; // Callback para notificar a UI sobre deadlocks
    private volatile boolean running = true;           // Flag para controlar a execução da thread

    public SistemaOperacional(long deltaT, Map<Integer, Recurso> recursos, Map<Integer, Processo> processos, Consumer<String> logConsumer, Consumer<List<Integer>> deadlockDetectedCallback) {
        this.deltaT = deltaT;
        this.recursos = recursos;
        this.processos = processos;
        this.logConsumer = logConsumer;
        this.deadlockDetectedCallback = deadlockDetectedCallback;
    }

    public void encerrar() {
        this.running = false;
        log("Sistema Operacional: Encerrando monitoramento.");
    }

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept("SO: " + message);
        }
    }

    public int sleepWork(long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }

        long startTime = System.currentTimeMillis();
        long endTime = startTime + durationMs;

        Random random = new Random();
        int lastGeneratedNumber = 0;

        while (System.currentTimeMillis() < endTime) {
            lastGeneratedNumber = random.nextInt(1000);
        }

        return lastGeneratedNumber;
    }

    @Override
    public void run() {
        log("Iniciado monitoramento de deadlocks a cada " + deltaT + " segundos.");
        while (running) {
            try {
                sleepWork(deltaT * 1000);
                if (!running) break;

                // Apenas detecta deadlock. A notificação de processos bloqueados
                // é agora responsabilidade do Semaphore do Recurso.
                detectarDeadlock();

            }  catch (Exception e) {
                log("Erro inesperado no Sistema Operacional: " + e.getMessage());
                e.printStackTrace();
                running = false;
            }
        }
    }


    /**
     * Implementa uma variação simplificada do algoritmo do banqueiro para detectar deadlock.
     * Analisa os recursos disponíveis e os recursos que cada processo alocou e solicita.
     * Notifica a UI com uma lista dos IDs dos processos envolvidos em deadlock.
     */
    private synchronized void detectarDeadlock() {
        List<Processo> processosAtivos = new ArrayList<>();
        for(Processo p : processos.values()) {
            // Um processo BLOQUEADO em um acquire() ainda é um processo ATIVO para fins de deadlock.
            // Para o algoritmo do banqueiro/detecção, precisamos saber o que ele *está esperando*.
            // O status 'BLOQUEADO' do Processo é definido ANTES de chamar Recurso.alocarInstancia().
            if (p.getStatus() == Processo.Status.RODANDO || p.getStatus() == Processo.Status.BLOQUEADO) {
                processosAtivos.add(p);
            }
        }

        if (processosAtivos.isEmpty()) {
            log("Nenhum processo ativo para verificar deadlock.");
            deadlockDetectedCallback.accept(new ArrayList<>());
            return;
        }

        int numRecursos = recursos.size();
        int numProcessos = processosAtivos.size();

        Map<Integer, Integer> recursoIdToIndex = new HashMap<>();
        List<Integer> recursoIdsOrdenados = new ArrayList<>(recursos.keySet());
        java.util.Collections.sort(recursoIdsOrdenados);
        for (int i = 0; i < recursoIdsOrdenados.size(); i++) {
            recursoIdToIndex.put(recursoIdsOrdenados.get(i), i);
        }

        // 1. Vetor de Recursos Disponíveis (Available)
        int[] available = new int[numRecursos];
        for (Recurso r : recursos.values()) {
            available[recursoIdToIndex.get(r.getId())] = r.getInstanciasDisponiveis();
        }

        // 2. Matriz de Alocação (Allocation)
        int[][] allocation = new int[numProcessos][numRecursos];
        for (int i = 0; i < numProcessos; i++) {
            Processo p = processosAtivos.get(i);
            for (Map.Entry<Integer, Integer> entry : p.getRecursosAlocados().entrySet()) {
                allocation[i][recursoIdToIndex.get(entry.getKey())] = entry.getValue();
            }
        }

        // 3. Matriz de Requisição (Request)
        int[][] request = new int[numProcessos][numRecursos];
        for (int i = 0; i < numProcessos; i++) {
            Processo p = processosAtivos.get(i);
            // Se o processo está bloqueado, ele está esperando por 1 instância do recurso que ele aguarda.
            if (p.getStatus() == Processo.Status.BLOQUEADO && p.getRecursoAguardando() != null) {
                request[i][recursoIdToIndex.get(p.getRecursoAguardando().getId())] = 1;
            }
        }

        // 4. Vetor de Finalização (Finish)
        boolean[] finish = new boolean[numProcessos];

        // 5. Vetor de Trabalho (Work)
        int[] work = Arrays.copyOf(available, numRecursos);

        log("Verificando deadlock...");
        log("Available (Work): " + Arrays.toString(work));
        log("Allocation: " + Arrays.deepToString(allocation));
        log("Request: " + Arrays.deepToString(request));

        int count = 0;
        boolean foundProcessToExecute;

        do {
            foundProcessToExecute = false;
            for (int i = 0; i < numProcessos; i++) {
                if (!finish[i]) {
                    boolean canExecute = true;
                    for (int j = 0; j < numRecursos; j++) {
                        if (request[i][j] > work[j]) {
                            canExecute = false;
                            break;
                        }
                    }

                    if (canExecute) {
                        for (int j = 0; j < numRecursos; j++) {
                            work[j] += allocation[i][j];
                        }
                        finish[i] = true;
                        foundProcessToExecute = true;
                        count++;
                    }
                }
            }
        } while (foundProcessToExecute);

        List<Integer> deadlockedProcesses = new ArrayList<>();
        if (count == numProcessos) {
            log("Nenhum deadlock detectado. Sistema está em estado seguro.");
            deadlockDetectedCallback.accept(new ArrayList<>());
        } else {
            for (int i = 0; i < numProcessos; i++) {
                if (!finish[i]) {
                    deadlockedProcesses.add(processosAtivos.get(i).getProcessId());
                }
            }
            log("DEADLOCK DETECTADO! Processos envolvidos: " + deadlockedProcesses);
            deadlockDetectedCallback.accept(deadlockedProcesses);
        }
    }
}