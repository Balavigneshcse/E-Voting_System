package Backend.model;

public class VoteRequest {

    private String voterId;
    private String candidate;

    // Default constructor
    public VoteRequest() {}

    // Constructor with fields
    public VoteRequest(String voterId, String candidate) {
        this.voterId = voterId;
        this.candidate = candidate;
    }

    // Getters & Setters
    public String getVoterId() {
        return voterId;
    }

    public void setVoterId(String voterId) {
        this.voterId = voterId;
    }

    public String getCandidate() {
        return candidate;
    }

    public void setCandidate(String candidate) {
        this.candidate = candidate;
    }
}