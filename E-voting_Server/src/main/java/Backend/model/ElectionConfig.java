package Backend.model;
import jakarta.persistence.*;

@Entity @Table(name = "election_config")
public class ElectionConfig {
    @Id private Integer id = 1;
    @Column(name = "current_cycle") private Integer currentCycle;
    @Column(name = "is_active")     private Boolean isActive;

    public Integer getId()           { return id; }
    public Integer getCurrentCycle() { return currentCycle; }
    public Boolean getIsActive()     { return isActive; }
    public void setCurrentCycle(Integer c) { this.currentCycle = c; }
    public void setIsActive(Boolean a)     { this.isActive = a; }
}