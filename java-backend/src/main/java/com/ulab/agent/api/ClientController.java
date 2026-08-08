package com.ulab.agent.api;

import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.services.ClientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A business's customers.
 *
 * Phone numbers and email addresses cross this boundary as plain text and are
 * encrypted the moment they reach the database. Anyone who can open the panel
 * can read them; there is no login, and the encryption is there to protect a
 * stolen backup rather than to keep the operator out.
 */
@RestController
@RequestMapping("/api/businesses/{businessId}/clients")
public class ClientController {

    private final ClientService clients;

    public ClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    public List<ClientDtos.ClientView> list(@PathVariable UUID businessId) {
        return clients.list(businessId);
    }

    /** The code a new customer would be given, so the form can suggest it. */
    @GetMapping("/next-code")
    public Map<String, String> nextCode(@PathVariable UUID businessId) {
        return Map.of("clientCode", clients.nextClientCode(businessId));
    }

    @GetMapping("/{clientId}")
    public ClientDtos.ClientView get(@PathVariable UUID businessId, @PathVariable UUID clientId) {
        return clients.get(businessId, clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientDtos.ClientView create(@PathVariable UUID businessId,
                                        @Valid @RequestBody ClientDtos.ClientUpsertRequest request) {
        return clients.create(businessId, request);
    }

    @PutMapping("/{clientId}")
    public ClientDtos.ClientView update(@PathVariable UUID businessId, @PathVariable UUID clientId,
                                        @Valid @RequestBody ClientDtos.ClientUpsertRequest request) {
        return clients.update(businessId, clientId, request);
    }

    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> delete(@PathVariable UUID businessId, @PathVariable UUID clientId) {
        clients.delete(businessId, clientId);
        return ResponseEntity.noContent().build();
    }
}
