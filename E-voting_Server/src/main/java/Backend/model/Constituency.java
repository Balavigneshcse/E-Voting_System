package Backend.model;
import jakarta.persistence.*;

@Entity @Table(name = "constituencies")
public class Constituency {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String name;
    private String state;

    public Integer getId()    { return id; }
    public String  getName()  { return name; }
    public String  getState() { return state; }
    public void setId(Integer id)       { this.id = id; }
    public void setName(String name)    { this.name = name; }
    public void setState(String state)  { this.state = state; }
}