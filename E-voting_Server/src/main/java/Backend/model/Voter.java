package Backend.model;

import jakarta.persistence.*;

/**
 * A registered voter.
 *
 * <p>Two columns matter more than the rest: {@code voterId} and {@code nfcCardId} are
 * how a terminal resolves a tapped card to a person, backed by the unique indexes in
 * {@code V5__voter_lookup_indexes.sql} so that resolution stays a single index seek
 * however large the electorate grows.
 *
 * <p>Which ballot a voter receives is derived from {@link #getLsConstituencyId()} (Lok
 * Sabha / PM elections) or {@link #getVsConstituencyId()} (Vidhan Sabha / CM elections)
 * by {@code VotingService}, never from the terminal a voter happens to be standing at.
 * That is what lets a voter cast a ballot at any booth.
 *
 * <p>Whether this voter has voted is deliberately not a property of this entity. It
 * lives in {@link VoterTurnout}, keyed by voter and election, because a voter votes in
 * many elections over time and because keeping it here would put ballot-adjacent state
 * on the one table an admin screen lists in full.
 */
@Entity
@Table(name = "voters")
public class Voter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voter_id", nullable = false, unique = true)
    private String voterId;

    @Column(name = "nfc_card_id", unique = true)
    private String nfcCardId;

    @Column(nullable = false)
    private String name;

    @Column(name = "aadhaar_number")
    private String aadhaarNumber;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "date_of_birth")
    private java.time.LocalDate dateOfBirth;

    @Column(name = "state_id")
    private Integer stateId;

    @Column(name = "ls_constituency_id")
    private Integer lsConstituencyId;

    @Column(name = "vs_constituency_id")
    private Integer vsConstituencyId;

    @Column(name = "municipality_ward")
    private Integer municipalityWard;

    @Column(name = "municipality_tier")
    private Integer municipalityTier;

    @Column(name = "council_id")
    private Integer councilId;

    @Column(name = "panchayat_id")
    private Integer panchayatId;

    @Column(name = "ward_local_id")
    private Integer wardLocalId;

    @Column(name = "card_active")
    private Boolean cardActive = true;

    @Column(name = "election_cycle")
    private Integer electionCycle = 1;

    @Column(name = "fingerprint_template", columnDefinition = "bytea")
    private byte[] fingerprintTemplate;

    @Column(name = "fingerprint_enrolled")
    private Boolean fingerprintEnrolled = false;

    @Column(name = "photo", columnDefinition = "bytea")
    private byte[] photo;

    @Column(name = "photo_type")
    private String photoType = "image/jpeg";

    public Long getId() {
        return id;
    }

    public String getVoterId() {
        return voterId;
    }

    public String getNfcCardId() {
        return nfcCardId;
    }

    public String getName() {
        return name;
    }

    public String getAadhaarNumber() {
        return aadhaarNumber;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public java.time.LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Integer getStateId() {
        return stateId;
    }

    public Integer getLsConstituencyId() {
        return lsConstituencyId;
    }

    public Integer getVsConstituencyId() {
        return vsConstituencyId;
    }

    public Integer getMunicipalityWard() {
        return municipalityWard;
    }

    public Integer getMunicipalityTier() {
        return municipalityTier;
    }

    public Integer getCouncilId() {
        return councilId;
    }

    public Integer getPanchayatId() {
        return panchayatId;
    }

    public Integer getWardLocalId() {
        return wardLocalId;
    }

    public Boolean getCardActive() {
        return cardActive;
    }

    public Integer getElectionCycle() {
        return electionCycle;
    }

    public byte[] getFingerprintTemplate() {
        return fingerprintTemplate;
    }

    public Boolean getFingerprintEnrolled() {
        return fingerprintEnrolled;
    }

    public byte[] getPhoto() {
        return photo;
    }

    public String getPhotoType() {
        return photoType;
    }

    public void setVoterId(String voterId) {
        this.voterId = voterId;
    }

    public void setNfcCardId(String nfcCardId) {
        this.nfcCardId = nfcCardId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setAadhaarNumber(String aadhaarNumber) {
        this.aadhaarNumber = aadhaarNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public void setDateOfBirth(java.time.LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public void setStateId(Integer stateId) {
        this.stateId = stateId;
    }

    public void setLsConstituencyId(Integer lsConstituencyId) {
        this.lsConstituencyId = lsConstituencyId;
    }

    public void setVsConstituencyId(Integer vsConstituencyId) {
        this.vsConstituencyId = vsConstituencyId;
    }

    public void setMunicipalityWard(Integer municipalityWard) {
        this.municipalityWard = municipalityWard;
    }

    public void setMunicipalityTier(Integer municipalityTier) {
        this.municipalityTier = municipalityTier;
    }

    public void setCouncilId(Integer councilId) {
        this.councilId = councilId;
    }

    public void setPanchayatId(Integer panchayatId) {
        this.panchayatId = panchayatId;
    }

    public void setWardLocalId(Integer wardLocalId) {
        this.wardLocalId = wardLocalId;
    }

    public void setCardActive(Boolean cardActive) {
        this.cardActive = cardActive;
    }

    public void setElectionCycle(Integer electionCycle) {
        this.electionCycle = electionCycle;
    }

    public void setFingerprintTemplate(byte[] fingerprintTemplate) {
        this.fingerprintTemplate = fingerprintTemplate;
    }

    public void setFingerprintEnrolled(Boolean fingerprintEnrolled) {
        this.fingerprintEnrolled = fingerprintEnrolled;
    }

    public void setPhoto(byte[] photo) {
        this.photo = photo;
    }

    public void setPhotoType(String photoType) {
        this.photoType = photoType;
    }
}
