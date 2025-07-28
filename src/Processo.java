import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class Processo extends Thread {
    public enum Status {
        RODANDO, BLOQUEADO, CONCLUIDO, ENCERRADO
    }

    private int id;
    private long deltaTs;
    private long deltaTu;
    private Map<Integer, Recurso> recursosDisponiveis;
    private Map<Integer, Integer> recursosAlocados; // Recurso ID -> Quantidade (Recursos atualmente detidos)
    private Recurso recursoAguardando; // Recurso que o processo está esperando AGORA
    private Status status;
    private Random random;
    private Consumer<String> logConsumer;
    private Consumer<Runnable> uiUpdateCallback;
    private volatile boolean running = true;

    private Semaphore simulationStartSemaphore; // Semáforo para controlar o início da simulação

    // NOVO: Conjunto de recursos que o processo DESEJA adquirir para completar sua tarefa
    // (Por exemplo, para simular que precisa de R1 e R2 para uma tarefa)
    // Para simplificar, vamos fazer ele tentar adquirir 2 recursos aleatórios e depois liberar ambos.
    private Set<Integer> recursosDesejadosIds = new HashSet<>();
    private int maxRecursosParaAdquirirPorCiclo = 2; // Tentará adquirir até 2 recursos por vez para sua "tarefa"

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

        // Ao criar o processo, ele "define" quais 2 recursos (aleatoriamente) ele vai querer neste ciclo.
        // Isso é crucial para o teste de deadlock, pois processos com IDs diferentes
        // podem ter "desejos" conflitantes que levam ao abraço mortal.
        List<Recurso> allResources = new ArrayList<>(recursosDisponiveis.values());
        if (allResources.size() >= 2) { // Precisa de pelo menos 2 tipos de recursos
            int idx1 = random.nextInt(allResources.size());
            int idx2 = random.nextInt(allResources.size());
            while(idx1 == idx2 && allResources.size() > 1) { // Garante recursos diferentes
                idx2 = random.nextInt(allResources.size());
            }
            recursosDesejadosIds.add(allResources.get(idx1).getId());
            recursosDesejadosIds.add(allResources.get(idx2).getId());
            maxRecursosParaAdquirirPorCiclo = recursosDesejadosIds.size(); // Ajusta para o número real de desejados
            log("Processo P" + id + " deseja adquirir recursos: " + recursosDesejadosIds);
        } else if (allResources.size() == 1) {
            recursosDesejadosIds.add(allResources.get(0).getId());
            maxRecursosParaAdquirirPorCiclo = 1;
            log("Processo P" + id + " deseja adquirir recurso: " + recursosDesejadosIds);
        } else {
            maxRecursosParaAdquirirPorCiclo = 0; // Não há recursos para desejar
            log("Processo P" + id + ": Não há recursos suficientes para definir desejos.");
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

    public void encerrar() {
        this.running = false;
        this.interrupt();
        log("Processo " + id + " está sendo encerrado.");
        liberarTodosRecursos();
        status = Status.ENCERRADO;
        updateUI();
    }

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

    @Override
    public void run() {
        log("Aguardando início da simulação...");
        status = Status.BLOQUEADO;
        updateUI();

        try {
            simulationStartSemaphore.acquire();
            log("Iniciado.");
            status = Status.RODANDO;
            updateUI();
        } catch (InterruptedException e) {
            log("Processo " + id + " interrompido antes do início da simulação. Encerrando.");
            running = false;
            Thread.currentThread().interrupt();
            liberarTodosRecursos();
            status = Status.ENCERRADO;
            updateUI();
            return;
        }

        while (running) {
            try {
                // Ciclo principal do processo: tentar adquirir seus recursos desejados
                // até que tenha todos eles ou seja bloqueado

                // Filtra os recursos desejados que ainda não foram alocados
                List<Integer> recursosPendentesIds = new ArrayList<>();
                for(Integer desiredId : recursosDesejadosIds) {
                    if (!recursosAlocados.containsKey(desiredId) || recursosAlocados.get(desiredId) == 0) {
                        recursosPendentesIds.add(desiredId);
                    }
                }

                if (recursosPendentesIds.isEmpty()) {
                    // Se o processo já tem todos os recursos desejados, ele os utiliza e depois libera.
                    log("Possui todos os recursos desejados: " + recursosAlocados.keySet() + ". Utilizando...");
                    updateUI();
                    sleepWork(deltaTu * 1000); // Utiliza todos os recursos juntos
                    liberarTodosRecursos(); // Libera TUDO
                    log("Liberou todos os recursos. Iniciando novo ciclo de solicitação.");
                    // Define novos recursos desejados para o próximo ciclo
                    definirNovosRecursosDesejados();
                    sleepWork(deltaTs * 1000); // Pausa antes de começar a solicitar novamente
                } else {
                    // Ainda precisa de recursos. Tenta adquirir um dos recursos pendentes.
                    // Escolhe um recurso pendente aleatoriamente para tentar adquirir.
                    Recurso recursoSolicitado = recursosDisponiveis.get(recursosPendentesIds.get(random.nextInt(recursosPendentesIds.size())));

                    if (recursoSolicitado == null) {
                        log("Recurso desejado (ID " + recursoSolicitado.getId() + ") não encontrado. Encerrando.");
                        running = false;
                        break;
                    }

                    log("Tentando adquirir recurso: " + recursoSolicitado.getNome() + " (pendentes: " + recursosPendentesIds + "). Possuídos: " + recursosAlocados.keySet());
                    recursoAguardando = recursoSolicitado;
                    status = Status.BLOQUEADO; // Marca como BLOQUEADO antes de tentar adquirir
                    updateUI();

                    recursoSolicitado.alocarInstancia(); // Bloqueia aqui se não disponível!

                    // Se chegou aqui, conseguiu alocar!
                    recursosAlocados.merge(recursoSolicitado.getId(), 1, Integer::sum);
                    log("Adquiriu recurso " + recursoSolicitado.getNome() + ". Possuídos: " + recursosAlocados.keySet());
                    recursoAguardando = null; // Não está mais aguardando
                    status = Status.RODANDO; // Voltou a rodar
                    updateUI();

                    // Se não adquiriu todos, espera um pouco e tenta o próximo.
                    if (recursosAlocados.size() < maxRecursosParaAdquirirPorCiclo) {
                        Thread.sleep(deltaTs * 1000); // Uma pausa menor entre tentativas de aquisição interna
                    }
                    // O loop continuará para tentar adquirir os recursos restantes.
                }

            } catch (InterruptedException e) {
                log("Processo " + id + " foi interrompido. Encerrando.");
                running = false;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("Erro inesperado no Processo " + id + ": " + e.getMessage());
                e.printStackTrace();
                running = false;
            }
        }
        status = Status.CONCLUIDO;
        liberarTodosRecursos();
        log("Concluído.");
        updateUI();
    }

    private void liberarRecurso(Recurso recurso) {
        if (recurso != null) {
            recurso.liberarInstancia();
            recursosAlocados.computeIfPresent(recurso.getId(), (key, val) -> val - 1);
            recursosAlocados.entrySet().removeIf(entry -> entry.getValue() <= 0);
        }
    }

    private void liberarTodosRecursos() {
        // Cria uma cópia das chaves para evitar ConcurrentModificationException
        for (Integer recursoId : new java.util.ArrayList<>(recursosAlocados.keySet())) {
            Recurso recurso = recursosDisponiveis.get(recursoId);
            if (recurso != null) {
                int instancias = recursosAlocados.getOrDefault(recursoId, 0);
                for (int i = 0; i < instancias; i++) {
                    liberarRecurso(recurso);
                }
                log("Liberou todas as " + instancias + " instâncias do recurso " + recurso.getNome() + ".");
            }
        }
        recursosAlocados.clear();
        updateUI();
    }

    // Define um novo conjunto de recursos aleatórios que o processo desejará para o próximo ciclo
    private void definirNovosRecursosDesejados() {
        recursosDesejadosIds.clear();
        List<Recurso> allResources = new ArrayList<>(recursosDisponiveis.values());
        if (allResources.size() >= 2) {
            int idx1 = random.nextInt(allResources.size());
            int idx2 = random.nextInt(allResources.size());
            while(idx1 == idx2 && allResources.size() > 1) {
                idx2 = random.nextInt(allResources.size());
            }
            recursosDesejadosIds.add(allResources.get(idx1).getId());
            recursosDesejadosIds.add(allResources.get(idx2).getId());
            maxRecursosParaAdquirirPorCiclo = recursosDesejadosIds.size();
        } else if (allResources.size() == 1) {
            recursosDesejadosIds.add(allResources.get(0).getId());
            maxRecursosParaAdquirirPorCiclo = 1;
        } else {
            maxRecursosParaAdquirirPorCiclo = 0;
        }
        log("Processo P" + id + " definiu novos desejos: " + recursosDesejadosIds);
    }
}