package com.exemplo.controller;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/estado")
public class EstadoController {

    private final IMap<String, String> estadoMap;

    @Autowired
    public EstadoController(HazelcastInstance hazelcastInstance) {
        this.estadoMap = hazelcastInstance.getMap("estado-compartilhado");
    }

    @PostMapping("/put")
    public String put(@RequestParam String chave, @RequestParam String valor) {
        estadoMap.put(chave, valor);
        return "Chave '" + chave + "' armazenada.";
    }

    @GetMapping("/get")
    public String get(@RequestParam String chave) {
        String valor = estadoMap.get(chave);
        return valor != null ? valor : "(não encontrado)";
    }

    @GetMapping("/todos")
    public Map<String, String> todos() {
        return estadoMap;
    }
}
