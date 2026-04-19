package Backend.blockchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

public class Block {

    private int index;
    private String voterId;
    private String candidate;
    private String previousHash;
    private String hash;
    private LocalDateTime timestamp;

    public Block(int index, String voterId, String candidate, String previousHash) {
        this.index = index;
        this.voterId = voterId;
        this.candidate = candidate;
        this.previousHash = previousHash;
        this.timestamp = LocalDateTime.now();
        this.hash = calculateHash();
    }

    public String calculateHash() {
        String data = index + voterId + candidate + previousHash + timestamp.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // Getters
    public int getIndex()          { return index; }
    public String getVoterId()     { return voterId; }
    public String getCandidate()   { return candidate; }
    public String getPreviousHash(){ return previousHash; }
    public String getHash()        { return hash; }
    public LocalDateTime getTimestamp() { return timestamp; }
}