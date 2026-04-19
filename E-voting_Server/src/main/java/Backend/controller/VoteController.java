package Backend.controller;

import Backend.model.VoteRequest;
import Backend.service.VoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin("*")
public class VoteController {

    @Autowired
    private VoteService voteService;

    @PostMapping("/vote")
    public String vote(@RequestBody VoteRequest request) {
        return voteService.castVote(request.getVoterId(), request.getCandidate());
    }
}
