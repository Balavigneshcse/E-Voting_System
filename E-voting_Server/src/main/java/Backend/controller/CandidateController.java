package Backend.controller;

import Backend.model.Candidate;
import Backend.repository.CandidateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin("*")
public class CandidateController {

    @Autowired
    private CandidateRepository repository;

    @GetMapping("/candidates")
    public List<Candidate> getCandidates(){
        return repository.findAll();
    }

}