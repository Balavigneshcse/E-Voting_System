package Backend.controller;

import Backend.blockchain.Block;
import Backend.blockchain.Blockchain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin("*")
public class BlockchainController {

    @Autowired
    private Blockchain blockchain;

    // View the entire chain
    @GetMapping("/blockchain")
    public List<Block> getChain() {
        return blockchain.getChain();
    }

    // Validate chain integrity
    @GetMapping("/blockchain/validate")
    public Map<String, Object> validateChain() {
        boolean valid = blockchain.isChainValid();
        return Map.of(
                "valid",   valid,
                "message", valid ? "Chain is intact — no tampering detected."
                        : "WARNING: Chain has been tampered with!"
        );
    }
}