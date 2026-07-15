package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Testes da Reserva: garante que cada fábrica produz a combinação correta
 * de flags (permitido / coordenado / ancora).
 */
public class ReservaTest {

    @Test
    public void testCoordenadoPermiteEmModoCoordenadoSemSerAncora() {
        Reserva r = Reserva.coordenado();

        assertTrue(r.isPermitido());
        assertTrue(r.isCoordenado());
        assertFalse(r.isAncora());
    }

    @Test
    public void testDescoordenadoPermiteForaDoModoCoordenado() {
        Reserva r = Reserva.descoordenado();

        assertTrue(r.isPermitido());
        assertFalse(r.isCoordenado());
        assertFalse(r.isAncora());
    }

    @Test
    public void testAncoraPermiteEhCoordenadaEhAncora() {
        // a âncora é tratada como coordenada ao aplicar a resposta
        Reserva r = Reserva.ancora();

        assertTrue(r.isPermitido());
        assertTrue(r.isCoordenado());
        assertTrue(r.isAncora());
    }

    @Test
    public void testNegadaNaoPermite() {
        Reserva r = Reserva.negada();

        assertFalse(r.isPermitido());
        assertFalse(r.isCoordenado());
        assertFalse(r.isAncora());
    }
}
