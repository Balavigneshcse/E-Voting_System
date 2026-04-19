package Backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "elections")
public class Election {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    @Column(name = "name_ta")
    private String nameTa;

    private String type;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "election_cycle")
    private Integer electionCycle = 1;

    @Column(name = "admin_role")
    private String adminRole;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Integer getId()              { return id; }
    public String  getName()            { return name; }
    public String  getNameTa()          { return nameTa; }
    public String  getType()            { return type; }
    public Boolean getIsActive()        { return isActive; }
    public Integer getElectionCycle()   { return electionCycle; }
    public String  getAdminRole()       { return adminRole; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Integer id)              { this.id = id; }
    public void setName(String name)           { this.name = name; }
    public void setNameTa(String n)            { this.nameTa = n; }
    public void setType(String type)           { this.type = type; }
    public void setIsActive(Boolean a)         { this.isActive = a; }
    public void setElectionCycle(Integer c)    { this.electionCycle = c; }
    public void setAdminRole(String r)         { this.adminRole = r; }
    public void setCreatedAt(LocalDateTime d)  { this.createdAt = d; }
}