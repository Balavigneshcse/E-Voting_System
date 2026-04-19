package Backend.controller;
import Backend.repository.VoterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
@RestController
@CrossOrigin("*")
public class VoterController {

    @Autowired
    private VoterRepository repository;

    @GetMapping("/login/{voterId}")
    public String login(@PathVariable String voterId){
        if(repository.existsByVoterId(voterId)){
            return "valid";
        } else {
            return "invalid";
        }
    }
}