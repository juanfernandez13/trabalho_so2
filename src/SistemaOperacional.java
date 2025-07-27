import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // Método para encerrar o monitoramento do SO
    public void encerrar() {
        this.running = false;
        log("Sistema Operacional: Encerrando monitoramento.");
    }

    // Registra uma mensagem no log da interface (executado na EDT via callback)
    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept("SO: " + message);
        }
    }

    @Override
    public void run() {
        log("Iniciado monitoramento de deadlocks a cada " + deltaT + " segundos.");
        while (running) { // Loop principal do SO
            try {
                // Intervalo de tempo entre as verificações de deadlock
                Thread.sleep(deltaT * 1000);
                if (!running) break; // Verifica a flag de execução após o sleep

                detectarDeadlock(); // Executa o algoritmo de detecção
                notificarProcessosBloqueados(); // Tenta acordar processos que podem estar esperando

            } catch (InterruptedException e) {
                // Se a thread for interrompida durante o sleep
                log("Sistema Operacional: Monitoramento interrompido.");
                running = false;
            } catch (Exception e) {
                // Captura outras exceções inesperadas
                log("Erro inesperado no Sistema Operacional: " + e.getMessage());
                e.printStackTrace(); // Imprime o stack trace para depuração
                running = false;
            }
        }
    }

    // Percorre os processos bloqueados e tenta "acordá-los" se o recurso aguardado estiver disponível
    private void notificarProcessosBloqueados() {
        // Cria uma cópia dos processos.values() para evitar ConcurrentModificationException
        // caso um processo seja removido enquanto iteramos.
        for (Processo p : new ArrayList<>(processos.values())) {
            // Verifica se o processo está bloqueado e se está aguardando por algum recurso
            if (p.getStatus() == Processo.Status.BLOQUEADO && p.getRecursoAguardando() != null) {
                Recurso recursoAguardado = p.getRecursoAguardando();
                // Sincroniza no recurso para garantir a consistência ao verificar instâncias disponíveis
                synchronized (recursoAguardado) {
                    if (recursoAguardado.getInstanciasDisponiveis() > 0) {
                        // Se houver instâncias disponíveis, tenta acordar o processo
                        p.awakenIfWaitingFor(recursoAguardado);
                    }
                }
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
        // Filtra apenas os processos que estão rodando ou bloqueados
        for(Processo p : processos.values()) {
            if (p.getStatus() == Processo.Status.RODANDO || p.getStatus() == Processo.Status.BLOQUEADO) {
                processosAtivos.add(p);
            }
        }

        if (processosAtivos.isEmpty()) {
            log("Nenhum processo ativo para verificar deadlock.");
            deadlockDetectedCallback.accept(new ArrayList<>()); // Notifica a UI que não há deadlock
            return;
        }

        int numRecursos = recursos.size();
        int numProcessos = processosAtivos.size();

        // Mapeia o ID do recurso para um índice de matriz (0 a numRecursos-1)
        // Isso é necessário porque os IDs de recurso podem não ser sequenciais a partir de 1
        Map<Integer, Integer> recursoIdToIndex = new HashMap<>();
        List<Integer> recursoIdsOrdenados = new ArrayList<>(recursos.keySet());
        // Ordena os IDs para garantir consistência no mapeamento de índices
        java.util.Collections.sort(recursoIdsOrdenados);
        for (int i = 0; i < recursoIdsOrdenados.size(); i++) {
            recursoIdToIndex.put(recursoIdsOrdenados.get(i), i);
        }


        // 1. Vetor de Recursos Disponíveis (Available)
        int[] available = new int[numRecursos];
        for (Recurso r : recursos.values()) {
            // Usa o mapeamento de ID do recurso para o índice correto
            available[recursoIdToIndex.get(r.getId())] = r.getInstanciasDisponiveis();
        }

        // 2. Matriz de Alocação (Allocation)
        // Linhas: processos, Colunas: recursos. Contém o número de instâncias de cada recurso alocado por cada processo.
        int[][] allocation = new int[numProcessos][numRecursos];
        // Não é necessário um mapa processoIdToIndex aqui se iterarmos diretamente sobre processosAtivos
        for (int i = 0; i < numProcessos; i++) {
            Processo p = processosAtivos.get(i);
            for (Map.Entry<Integer, Integer> entry : p.getRecursosAlocados().entrySet()) {
                // Usa o mapeamento de ID do recurso para o índice correto
                allocation[i][recursoIdToIndex.get(entry.getKey())] = entry.getValue();
            }
        }

        // 3. Matriz de Requisição (Request)
        // Linhas: processos, Colunas: recursos. Contém o número de instâncias de cada recurso que cada processo precisa.
        // Para simplificar, consideramos que um processo bloqueado precisa de 1 instância do recurso que ele está aguardando.
        int[][] request = new int[numProcessos][numRecursos];
        for (int i = 0; i < numProcessos; i++) {
            Processo p = processosAtivos.get(i);
            if (p.getStatus() == Processo.Status.BLOQUEADO && p.getRecursoAguardando() != null) {
                // Usa o mapeamento de ID do recurso para o índice correto
                request[i][recursoIdToIndex.get(p.getRecursoAguardando().getId())] = 1; // Assume que precisa de 1 instância
            }
        }

        // 4. Vetor de Finalização (Finish)
        boolean[] finish = new boolean[numProcessos]; // True se o processo pode terminar, False caso contrário (inicialmente tudo false)

        // 5. Vetor de Trabalho (Work)
        int[] work = Arrays.copyOf(available, numRecursos); // Começa com os recursos disponíveis

        log("Verificando deadlock...");
        log("Available (Work): " + Arrays.toString(work));
        log("Allocation: " + Arrays.deepToString(allocation));
        log("Request: " + Arrays.deepToString(request));

        int count = 0; // Conta quantos processos podem terminar
        boolean foundProcessToExecute; // Flag para controlar o loop principal

        do {
            foundProcessToExecute = false;
            for (int i = 0; i < numProcessos; i++) {
                // Se o processo ainda não pode terminar
                if (!finish[i]) {
                    boolean canExecute = true;
                    // Verifica se o processo pode satisfazer suas requisições com os recursos disponíveis (work)
                    for (int j = 0; j < numRecursos; j++) {
                        if (request[i][j] > work[j]) {
                            canExecute = false; // Não pode executar se a requisição for maior que o work disponível
                            break;
                        }
                    }

                    if (canExecute) {
                        // Se o processo pode executar, ele "termina" e libera seus recursos alocados para 'work'
                        for (int j = 0; j < numRecursos; j++) {
                            work[j] += allocation[i][j];
                        }
                        finish[i] = true;        // Marca o processo como finalizável
                        foundProcessToExecute = true; // Indica que um processo foi encontrado para executar
                        count++;                 // Incrementa o contador de processos que podem terminar
                    }
                }
            }
        } while (foundProcessToExecute); // Continua enquanto houver processos que podem ser executados

        List<Integer> deadlockedProcesses = new ArrayList<>();
        if (count == numProcessos) {
            log("Nenhum deadlock detectado. Sistema está em estado seguro.");
            deadlockDetectedCallback.accept(new ArrayList<>()); // Envia lista vazia para a UI
        } else {
            // Se nem todos os processos puderam terminar, os restantes estão em deadlock
            for (int i = 0; i < numProcessos; i++) {
                if (!finish[i]) {
                    deadlockedProcesses.add(processosAtivos.get(i).getProcessId());
                }
            }
            log("DEADLOCK DETECTADO! Processos envolvidos: " + deadlockedProcesses);
            deadlockDetectedCallback.accept(deadlockedProcesses); // Notifica a UI com os IDs dos processos em deadlock
        }
    }
}