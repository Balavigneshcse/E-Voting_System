package Backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One election cycle for one office — a PM (Lok Sabha) or CM (Vidhan Sabha) election,
 * or a municipal election.
 *
 * <p>Exactly one election is expected to be {@link #getIsActive()} at a time; that is
 * the election a terminal issues ballots for. {@link #getElectionCycle()} distinguishes
 * repeats of the same office over time, so turnout and results stay scoped to the run
 * they belong to rather than accumulating across cycles.
 */
@Entity
@Table(name = "elections")
public class Election {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    @Column(name = "name_ta")
    private String nameTa;

    /** {@code PM}, {@code CM}, or a municipal election type. */
    private String type;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "election_cycle")
    private Integer electionCycle = 1;

    @Column(name = "admin_role")
    private String adminRole;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameTa() {
        return nameTa;
    }

    public String getType() {
        return type;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public Integer getElectionCycle() {
        return electionCycle;
    }

    public String getAdminRole() {
        return adminRole;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setNameTa(String nameTa) {
        this.nameTa = nameTa;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public void setElectionCycle(Integer electionCycle) {
        this.electionCycle = electionCycle;
    }

    public void setAdminRole(String adminRole) {
        this.adminRole = adminRole;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
