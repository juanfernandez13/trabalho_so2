import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.List; // Importação adicionada

public class Processo extends Thread {
    public enum Status {
        RODANDO, BLOQUEADO, CONCLUIDO, ENCERRADO // Adicionado ENCERRADO para clareza
    }

    private int id;
    private long deltaTs; // Intervalo de tempo de solicitação (em segundos)
    private long deltaTu; // Intervalo de tempo de utilização (em segundos)
    private Map<Integer, Recurso> recursosDisponiveis; // Referência a todos os recursos do sistema
    private Map<Integer, Integer> recursosAlocados;    // Recursos que este processo possui (Recurso ID -> Quantidade)
    private Recurso recursoAguardando;                 // Recurso que o processo está esperando, se estiver bloqueado
    private Status status;
    private Random random;
    private Consumer<String> logConsumer;              // Callback para registrar mensagens na UI
    private Consumer<Runnable> uiUpdateCallback;       // Callback para atualizar a UI na EDT
    private volatile boolean running = true;           // Flag para controlar a execução da thread
    private Semaphore blockSemaphore = new Semaphore(0); // Semáforo para bloquear/desbloquear o processo

    public Processo(int id, long deltaTs, long deltaTu, Map<Integer, Recurso> recursosDisponiveis, Consumer<String> logConsumer, Consumer<Runnable> uiUpdateCallback) {
        this.id = id;
        this.deltaTs = deltaTs;
        this.deltaTu = deltaTu;
        this.recursosDisponiveis = recursosDisponiveis;
        this.recursosAlocados = new ConcurrentHashMap<>();
        this.status = Status.RODANDO;
        this.random = new Random();
        this.logConsumer = logConsumer;
        this.uiUpdateCallback = uiUpdateCallback;
    }

    public int getProcessId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public Recurso getRecursoAguardando() {
        return recursoAguardando;
    }

    public Map<Integer, Integer> getRecursosAlocados() {
        return recursosAlocados;
    }

    // Método para encerrar o processo de forma controlada
    public void encerrar() {
        this.running = false;
        // Se o processo estiver bloqueado, libera-o para que possa sair do 'acquire()' e terminar
        if (status == Status.BLOQUEADO) {
            blockSemaphore.release();
        }
        log("Processo " + id + " está sendo encerrado.");
        liberarTodosRecursos(); // Libera quaisquer recursos que o processo possa estar segurando
        status = Status.ENCERRADO;
        updateUI();
    }

    // Registra uma mensagem no log da interface (executado na EDT via callback)
    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept("P" + id + ": " + message);
        }
    }

    // Solicita uma atualização da interface gráfica (executado na EDT via callback)
    private void updateUI() {
        if (uiUpdateCallback != null) {
            uiUpdateCallback.accept(() -> {}); // Executa um Runnable vazio para forçar a atualização da UI
        }
    }

    @Override
    public void run() {
        log("Iniciado.");
        while (running) { // Loop principal do processo
            try {
                // Intervalo de tempo entre as solicitações de recurso
                Thread.sleep(deltaTs * 1000);

                if (!running) break; // Verifica a flag de execução após o sleep

                // Escolhe um recurso aleatoriamente para solicitar
                if (recursosDisponiveis.isEmpty()) {
                    log("Não há recursos configurados para solicitar. Encerrando.");
                    break; // Sai do loop se não houver recursos no sistema
                }
                List<Recurso> recursosList = new java.util.ArrayList<>(recursosDisponiveis.values());
                if (recursosList.isEmpty()) { // Garante que a lista não esteja vazia após a cópia
                    log("Lista de recursos disponível vazia. Encerrando.");
                    break;
                }
                Recurso recursoSolicitado = recursosList.get(random.nextInt(recursosList.size()));

                log("Solicitando recurso " + recursoSolicitado.getNome() + ".");
                status = Status.RODANDO; // Presume rodando antes de tentar alocar
                updateUI();

                // Sincroniza no objeto Recurso para garantir que a alocação/liberação
                // de instâncias (através do Semaphore) seja feita de forma consistente
                // e para que outros processos que estejam no 'synchronized' por este recurso esperem.
                // Embora Semaphore já seja thread-safe, este 'synchronized' aqui serve para
                // garantir que o bloco de código completo (tentar alocar, mudar status, etc.)
                // para um dado recurso seja executado atomicamente por um processo.
                synchronized (recursoSolicitado) {
                    if (recursoSolicitado.alocarInstancia()) { // Tenta adquirir 1 permissão do Semaphore
                        recursosAlocados.merge(recursoSolicitado.getId(), 1, Integer::sum);
                        log("Recebeu recurso " + recursoSolicitado.getNome() + ". Utilizando...");
                        updateUI();
                        // Intervalo de tempo de utilização do recurso
                        Thread.sleep(deltaTu * 1000);
                        liberarRecurso(recursoSolicitado);
                        log("Liberou recurso " + recursoSolicitado.getNome() + ".");
                    } else {
                        // Recurso não disponível, processo se bloqueia
                        log("Recurso " + recursoSolicitado.getNome() + " não disponível. Bloqueado.");
                        recursoAguardando = recursoSolicitado;
                        status = Status.BLOQUEADO;
                        updateUI();
                        try {
                            // Processo espera aqui até que o Sistema Operacional o acorde
                            blockSemaphore.acquire();
                            log("Desbloqueado e tentando alocar novamente o recurso " + recursoSolicitado.getNome());

                            // Após ser acordado, tenta alocar novamente o recurso.
                            // É importante re-sincronizar no recurso para garantir que
                            // a nova tentativa de alocação seja consistente.
                            synchronized (recursoSolicitado) {
                                if (recursoSolicitado.alocarInstancia()) {
                                    recursosAlocados.merge(recursoSolicitado.getId(), 1, Integer::sum);
                                    log("Recebeu recurso " + recursoSolicitado.getNome() + " após desbloqueio. Utilizando...");
                                    updateUI();
                                    Thread.sleep(deltaTu * 1000);
                                    liberarRecurso(recursoSolicitado);
                                    log("Liberou recurso " + recursoSolicitado.getNome() + ".");
                                } else {
                                    // Pode acontecer se outro processo pegou o recurso logo após o 'release'
                                    log("Recurso " + recursoSolicitado.getNome() + " ainda não disponível após desbloqueio. Tentará novamente no próximo ciclo.");
                                }
                            }
                        } catch (InterruptedException e) {
                            // Se a thread for interrompida enquanto bloqueada (ex: por um processo sendo removido)
                            log("Processo " + id + " foi interrompido enquanto bloqueado. Encerrando.");
                            running = false; // Define a flag para sair do loop
                        } finally {
                            recursoAguardando = null; // Limpa o recurso aguardado
                            status = Status.RODANDO; // Retorna ao status de rodando (se não foi encerrado)
                            updateUI();
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Se a thread for interrompida durante o sleep principal
                log("Processo " + id + " foi interrompido. Encerrando.");
                running = false;
            } catch (Exception e) {
                // Captura outras exceções inesperadas
                log("Erro inesperado no Processo " + id + ": " + e.getMessage());
                e.printStackTrace(); // Imprime o stack trace para depuração
                running = false;
            }
        }
        status = Status.CONCLUIDO; // Processo concluiu sua execução normal
        liberarTodosRecursos();
        log("Concluído.");
        updateUI();
    }

    // Libera uma única instância de um recurso que o processo alocou
    private void liberarRecurso(Recurso recurso) {
        synchronized (recurso) { // Sincroniza para consistência com o Semaphore
            recurso.liberarInstancia(); // Chama o método do Recurso para liberar a permissão
            // Decrementa a contagem de recursos alocados para este processo
            recursosAlocados.computeIfPresent(recurso.getId(), (key, val) -> val - 1);
            // Remove a entrada se a contagem chegar a zero ou menos
            recursosAlocados.entrySet().removeIf(entry -> entry.getValue() <= 0);
        }
    }

    // Libera todas as instâncias de todos os recursos que o processo possui
    private void liberarTodosRecursos() {
        // Cria uma cópia das chaves para evitar ConcurrentModificationException
        for (Integer recursoId : new java.util.ArrayList<>(recursosAlocados.keySet())) {
            Recurso recurso = recursosDisponiveis.get(recursoId);
            if (recurso != null) {
                int instancias = recursosAlocados.getOrDefault(recursoId, 0);
                for (int i = 0; i < instancias; i++) {
                    recurso.liberarInstancia(); // Libera cada instância alocada
                }
                log("Liberou todas as " + instancias + " instâncias do recurso " + recurso.getNome() + ".");
            }
        }
        recursosAlocados.clear(); // Limpa o mapa de recursos alocados pelo processo
        updateUI();
    }

    // Método chamado pelo Sistema Operacional para acordar um processo bloqueado
    public void awakenIfWaitingFor(Recurso liberatedResource) {
        // Verifica se o processo está bloqueado E aguardando por este recurso específico
        if (status == Status.BLOQUEADO && recursoAguardando != null && recursoAguardando.equals(liberatedResource)) {
            blockSemaphore.release(); // Libera uma permissão no semáforo de bloqueio, acordando o processo
        }
    }
}