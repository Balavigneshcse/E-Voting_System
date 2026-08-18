package Backend.model;

import jakarta.persistence.*;

/**
 * A candidate standing in one election, for one constituency (or, for a
 * municipal election, one ward within a tier).
 *
 * <p>Which candidates a voter sees is resolved by {@code VotingService#ballotFor}
 * matching {@link #getElectionId()} against the voter's active election and
 * {@link #getConstituencyId()} against whichever of the voter's two constituency
 * fields that election type uses — backed by the composite index added in
 * {@code V5__voter_lookup_indexes.sql}, since this lookup runs once per voter,
 * on every session start.
 */
@Entity
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    /** Tamil name, used by the terminal's bilingual display. */
    @Column(name = "name_ta")
    private String nameTa;

    private String party;

    @Column(name = "party_ta")
    private String partyTa;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "date_of_birth")
    private java.time.LocalDate dateOfBirth;

    @Column(name = "election_id", nullable = false)
    private Integer electionId;

    @Column(name = "state_id")
    private Integer stateId;

    @Column(name = "constituency_id")
    private Integer constituencyId;

    @Column(name = "municipality_tier")
    private Integer municipalityTier;

    @Column(name = "ward_id")
    private Integer wardId;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "symbol_url")
    private String symbolUrl;

    /** Hex colour the terminal renders this candidate's ballot button in. */
    @Column(name = "party_color")
    private String partyColor;

    @Column(name = "photo_data", columnDefinition = "bytea")
    private byte[] photoData;

    @Column(name = "photo_type")
    private String photoType;

    @Column(name = "symbol_data", columnDefinition = "bytea")
    private byte[] symbolData;

    @Column(name = "symbol_type")
    private String symbolType;

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameTa() {
        return nameTa;
    }

    public String getParty() {
        return party;
    }

    public String getPartyTa() {
        return partyTa;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public java.time.LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Integer getElectionId() {
        return electionId;
    }

    public Integer getStateId() {
        return stateId;
    }

    public Integer getConstituencyId() {
        return constituencyId;
    }

    public Integer getMunicipalityTier() {
        return municipalityTier;
    }

    public Integer getWardId() {
        return wardId;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public String getSymbolUrl() {
        return symbolUrl;
    }

    public String getPartyColor() {
        return partyColor;
    }

    public byte[] getPhotoData() {
        return photoData;
    }

    public String getPhotoType() {
        return photoType;
    }

    public byte[] getSymbolData() {
        return symbolData;
    }

    public String getSymbolType() {
        return symbolType;
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

    public void setParty(String party) {
        this.party = party;
    }

    public void setPartyTa(String partyTa) {
        this.partyTa = partyTa;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public void setDateOfBirth(java.time.LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public void setElectionId(Integer electionId) {
        this.electionId = electionId;
    }

    public void setStateId(Integer stateId) {
        this.stateId = stateId;
    }

    public void setConstituencyId(Integer constituencyId) {
        this.constituencyId = constituencyId;
    }

    public void setMunicipalityTier(Integer municipalityTier) {
        this.municipalityTier = municipalityTier;
    }

    public void setWardId(Integer wardId) {
        this.wardId = wardId;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public void setSymbolUrl(String symbolUrl) {
        this.symbolUrl = symbolUrl;
    }

    public void setPartyColor(String partyColor) {
        this.partyColor = partyColor;
    }

    public void setPhotoData(byte[] photoData) {
        this.photoData = photoData;
    }

    public void setPhotoType(String photoType) {
        this.photoType = photoType;
    }

    public void setSymbolData(byte[] symbolData) {
        this.symbolData = symbolData;
    }

    public void setSymbolType(String symbolType) {
        this.symbolType = symbolType;
    }
}
