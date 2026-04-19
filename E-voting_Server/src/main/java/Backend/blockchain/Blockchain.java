package Backend.blockchain;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component   // ← THIS is what makes Spring manage it as a singleton bean
public class Blockchain {

    private final List<Block> chain = new ArrayList<>();

    public Blockchain() {
        chain.add(createGenesisBlock());
    }

    private Block createGenesisBlock() {
        return new Block(0, "GENESIS", "NONE", "0");
    }

    public Block getLatestBlock() {
        return chain.get(chain.size() - 1);
    }

    public synchronized void addVote(String voterId, String candidate) {
        Block newBlock = new Block(
                chain.size(),
                voterId,
                candidate,
                getLatestBlock().getHash()
        );
        chain.add(newBlock);
    }

    public List<Block> getChain() {
        return chain;
    }

    // Checks that no block has been tampered with
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current  = chain.get(i);
            Block previous = chain.get(i - 1);

            // Re-compute hash and compare
            if (!current.getHash().equals(current.calculateHash())) {
                return false;
            }
            // Check the chain linkage
            if (!current.getPreviousHash().equals(previous.getHash())) {
                return false;
            }
        }
        return true;
    }
}