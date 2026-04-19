package Backend.model;

import jakarta.persistence.*;

@Entity
public class Candidate {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Integer id;
    private String name;
    @Column(name="name_ta")  private String nameTa;
    private String party;
    @Column(name="party_ta") private String partyTa;
    @Column(name="election_id")       private Integer electionId;
    @Column(name="state_id")          private Integer stateId;
    @Column(name="constituency_id")   private Integer constituencyId;
    @Column(name="municipality_tier") private Integer municipalityTier;
    @Column(name="ward_id")           private Integer wardId;
    @Column(name="photo_url")         private String photoUrl;
    @Column(name="symbol_url")        private String symbolUrl;
    @Column(name="party_color")       private String partyColor;
    @Column(name="photo_data", columnDefinition="bytea")   private byte[] photoData;
    @Column(name="photo_type")        private String photoType;
    @Column(name="symbol_data", columnDefinition="bytea")  private byte[] symbolData;
    @Column(name="symbol_type")       private String symbolType;

    public Integer getId()               { return id; }
    public String  getName()             { return name; }
    public String  getNameTa()           { return nameTa; }
    public String  getParty()            { return party; }
    public String  getPartyTa()          { return partyTa; }
    public Integer getElectionId()       { return electionId; }
    public Integer getStateId()          { return stateId; }
    public Integer getConstituencyId()   { return constituencyId; }
    public Integer getMunicipalityTier() { return municipalityTier; }
    public Integer getWardId()           { return wardId; }
    public String  getPhotoUrl()         { return photoUrl; }
    public String  getSymbolUrl()        { return symbolUrl; }
    public String  getPartyColor()       { return partyColor; }
    public byte[]  getPhotoData()        { return photoData; }
    public String  getPhotoType()        { return photoType; }
    public byte[]  getSymbolData()       { return symbolData; }
    public String  getSymbolType()       { return symbolType; }

    public void setId(Integer v)              { id=v; }
    public void setName(String v)             { name=v; }
    public void setNameTa(String v)           { nameTa=v; }
    public void setParty(String v)            { party=v; }
    public void setPartyTa(String v)          { partyTa=v; }
    public void setElectionId(Integer v)      { electionId=v; }
    public void setStateId(Integer v)         { stateId=v; }
    public void setConstituencyId(Integer v)  { constituencyId=v; }
    public void setMunicipalityTier(Integer v){ municipalityTier=v; }
    public void setWardId(Integer v)          { wardId=v; }
    public void setPhotoUrl(String v)         { photoUrl=v; }
    public void setSymbolUrl(String v)        { symbolUrl=v; }
    public void setPartyColor(String v)       { partyColor=v; }
    public void setPhotoData(byte[] v)        { photoData=v; }
    public void setPhotoType(String v)        { photoType=v; }
    public void setSymbolData(byte[] v)       { symbolData=v; }
    public void setSymbolType(String v)       { symbolType=v; }
}