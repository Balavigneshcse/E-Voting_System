package Backend.model;
import jakarta.persistence.*;

@Entity
@Table(name="voters")
public class Voter {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="voter_id",nullable=false,unique=true) private String voterId;
    @Column(name="nfc_card_id",unique=true)             private String nfcCardId;
    @Column(nullable=false)                             private String name;
    @Column(name="aadhaar_number")                      private String aadhaarNumber;
    @Column(name="state_id")                            private Integer stateId;
    @Column(name="ls_constituency_id")                  private Integer lsConstituencyId;
    @Column(name="vs_constituency_id")                  private Integer vsConstituencyId;
    @Column(name="municipality_ward")                   private Integer municipalityWard;
    @Column(name="municipality_tier")                   private Integer municipalityTier;
    @Column(name="council_id")                          private Integer councilId;
    @Column(name="panchayat_id")                        private Integer panchayatId;
    @Column(name="ward_local_id")                       private Integer wardLocalId;
    @Column(name="card_active")                         private Boolean cardActive=true;
    @Column(name="election_cycle")                      private Integer electionCycle=1;
    @Column(name="fingerprint_template", columnDefinition="bytea")           private byte[] fingerprintTemplate;
    @Column(name="fingerprint_enrolled")                private Boolean fingerprintEnrolled=false;
    // Photo stored as bytea in DB
    @Column(name="photo", columnDefinition="bytea")                          private byte[] photo;
    @Column(name="photo_type")                          private String photoType="image/jpeg";

    public Long    getId()                  { return id; }
    public String  getVoterId()             { return voterId; }
    public String  getNfcCardId()           { return nfcCardId; }
    public String  getName()                { return name; }
    public String  getAadhaarNumber()       { return aadhaarNumber; }
    public Integer getStateId()             { return stateId; }
    public Integer getLsConstituencyId()    { return lsConstituencyId; }
    public Integer getVsConstituencyId()    { return vsConstituencyId; }
    public Integer getMunicipalityWard()    { return municipalityWard; }
    public Integer getMunicipalityTier()    { return municipalityTier; }
    public Integer getCouncilId()           { return councilId; }
    public Integer getPanchayatId()         { return panchayatId; }
    public Integer getWardLocalId()         { return wardLocalId; }
    public Boolean getCardActive()          { return cardActive; }
    public Integer getElectionCycle()       { return electionCycle; }
    public byte[]  getFingerprintTemplate() { return fingerprintTemplate; }
    public Boolean getFingerprintEnrolled() { return fingerprintEnrolled; }
    public byte[]  getPhoto()               { return photo; }
    public String  getPhotoType()           { return photoType; }
    // compat
    public Boolean getHasVoted()            { return false; }
    public Integer getConstituencyId()      { return vsConstituencyId; }

    public void setVoterId(String v)              { voterId=v; }
    public void setNfcCardId(String v)            { nfcCardId=v; }
    public void setName(String v)                 { name=v; }
    public void setAadhaarNumber(String v)        { aadhaarNumber=v; }
    public void setStateId(Integer v)             { stateId=v; }
    public void setLsConstituencyId(Integer v)    { lsConstituencyId=v; }
    public void setVsConstituencyId(Integer v)    { vsConstituencyId=v; }
    public void setMunicipalityWard(Integer v)    { municipalityWard=v; }
    public void setMunicipalityTier(Integer v)    { municipalityTier=v; }
    public void setCouncilId(Integer v)           { councilId=v; }
    public void setPanchayatId(Integer v)         { panchayatId=v; }
    public void setWardLocalId(Integer v)         { wardLocalId=v; }
    public void setCardActive(Boolean v)          { cardActive=v; }
    public void setElectionCycle(Integer v)       { electionCycle=v; }
    public void setFingerprintTemplate(byte[] v)  { fingerprintTemplate=v; }
    public void setFingerprintEnrolled(Boolean v) { fingerprintEnrolled=v; }
    public void setPhoto(byte[] v)                { photo=v; }
    public void setPhotoType(String v)            { photoType=v; }
    public void setHasVoted(Boolean v)            { }
    public void setConstituencyId(Integer v)      { vsConstituencyId=v; }
}