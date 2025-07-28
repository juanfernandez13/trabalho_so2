import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit; // Adicionado para tryAcquire com timeout, útil para deadlocks

public class Recurso {
    private String nome;
    private int id;
    private int totalInstancias;
    private Semaphore instanciasDisponiveis; // Semáforo para gerenciar permissões

    public Recurso(String nome, int id, int totalInstancias) {
        this.nome = nome;
        this.id = id;
        this.totalInstancias = totalInstancias;
        this.instanciasDisponiveis = new Semaphore(totalInstancias); // Inicializa com o total de instâncias
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

    public int getInstanciasDisponiveis() {
        return instanciasDisponiveis.availablePermits();
    }

    // Aloca uma instância do recurso.
    // Esta função AGORA BLOQUEIA a thread se o recurso não estiver disponível.
    // Lança InterruptedException se a thread for interrompida enquanto espera.
    public void alocarInstancia() throws InterruptedException {
        instanciasDisponiveis.acquire();
    }

    // Tenta alocar uma instância com um timeout. Retorna true se conseguiu, false se timeout.
    // Não é usado na lógica atual do Processo, mas pode ser útil para outras estratégias.
    public boolean tentarAlocarInstancia(long timeout, TimeUnit unit) throws InterruptedException {
        return instanciasDisponiveis.tryAcquire(timeout, unit);
    }

    // Libera uma instância do recurso.
    public void liberarInstancia() {
        instanciasDisponiveis.release();
    }

    @Override
    public String toString() {
        return nome + " (ID: " + id + ", Disp: " + instanciasDisponiveis.availablePermits() + "/" + totalInstancias + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recurso recurso = (Recurso) o;
        return id == recurso.id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}