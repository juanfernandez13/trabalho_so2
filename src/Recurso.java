import java.util.concurrent.Semaphore;

public class Recurso {
    private String nome;
    private int id;
    private int totalInstancias;
    private Semaphore instanciasDisponiveis; // Agora um objeto Semaphore

    public Recurso(String nome, int id, int totalInstancias) {
        this.nome = nome;
        this.id = id;
        this.totalInstancias = totalInstancias;
        // CORREÇÃO CRÍTICA: Inicialize o Semaphore AQUI!
        // O construtor do Semaphore já inicializa com o número de permissões disponíveis.
        this.instanciasDisponiveis = new Semaphore(totalInstancias);
    }

    public String getNome() {
        return nome;
    }

    public int getId() {
        return id;
    }

    public int getTotalInstancias() {
        return totalInstancias;
    }

    // Não precisa de 'synchronized' aqui, availablePermits() já é thread-safe
    public int getInstanciasDisponiveis() {
        return instanciasDisponiveis.availablePermits();
    }

    // Não precisa de 'synchronized' neste método porque Semaphore.tryAcquire() já é atômico
    public boolean alocarInstancia() {
        // Tenta adquirir uma permissão. Retorna true se conseguiu imediatamente, false caso contrário.
        // Isso é crucial para a lógica do Processo, que precisa saber se conseguiu ou não para então se bloquear.
        return instanciasDisponiveis.tryAcquire();
    }

    // Não precisa de 'synchronized' neste método porque Semaphore.release() já é atômico
    public void liberarInstancia() {
        // Simplesmente libera uma permissão. O Semaphore gerencia a contagem.
        // A condição 'instanciasDisponiveis.availablePermits() < totalInstancias' não é estritamente necessária
        // para o Semaphore funcionar, mas pode ser uma verificação lógica se quisermos
        // garantir que não liberamos mais do que o total_instancias (o Semaphore por si só não impede isso).
        // No entanto, para a simulação, basta liberar.
        instanciasDisponiveis.release();
    }

    @Override
    public String toString() {
        // Usa availablePermits() para obter o número de instâncias disponíveis
        return nome + " (ID: " + id + ", Disp: " + instanciasDisponiveis.availablePermits() + "/" + totalInstancias + ")";
    }

    // Adicionado método equals para comparação de recursos (útil para Processo.awakenIfWaitingFor)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recurso recurso = (Recurso) o;
        return id == recurso.id; // Recursos são iguais se tiverem o mesmo ID
    }

    @Override
    public int hashCode() {
        return id;
    }
}