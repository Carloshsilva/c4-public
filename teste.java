package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Testes de unidade da Cota. Objetos reais, sem mocks (padrão do projeto).
 * Valida os verbos e, em especial, a regra de renovar vs. preservar o contador.
 */
public class CotaTest {

    private static final int DOCUMENTAL = 300;

    @Test
    public void testColdStartPartindoDoDocumental() {
        Cota cota = new Cota("Comp", DOCUMENTAL);

        assertFalse(cota.dadosReais);
        assertEquals(300, cota.limite);
        assertEquals(300, cota.restante);
        assertEquals(0, cota.reset);
        assertEquals(0, cota.contador);
        assertFalse(cota.bloqueado);
        assertEquals(0L, cota.fimBloqueio);
    }

    @Test
    public void testIncrementaSobeApenasOContador() {
        Cota cota = new Cota("A", DOCUMENTAL);

        cota.incrementa();
        cota.incrementa();

        assertEquals(2, cota.contador);
    }

    @Test
    public void testAtualizaComHeaderNaoTocaNoContador() {
        Cota cota = new Cota("A", DOCUMENTAL);
        cota.incrementa(); // contador = 1

        cota.atualizaComHeader(300, 250, 60);

        assertTrue(cota.dadosReais);
        assertEquals(300, cota.limite);
        assertEquals(250, cota.restante);
        assertEquals(60, cota.reset);
        assertEquals(1, cota.contador); // inalterado
    }

    @Test
    public void testRenovaOuPreservaNoColdStartPreserva() {
        // sem dadosReais ainda: nunca é "virada de janela" -> preserva (max)
        Cota cota = new Cota("Comp", DOCUMENTAL);
        cota.incrementa(); // contador = 1

        cota.renovaOuPreservaContador(300, 299, 60); // L-rem = 1

        assertEquals(1, cota.contador); // max(1, 1)
    }

    @Test
    public void testRenovaOuPreservaMesmaJanelaPreservaConsumo() {
        // rem CAIU (mesma janela): preserva o maior consumo conhecido
        Cota cota = new Cota("Comp", DOCUMENTAL);
        cota.atualizaComHeader(300, 100, 30); // dadosReais=true, rem=100, reset=30
        cota.contador = 200;                  // já consumido 200 nesta janela

        // reducao/consumo: rem vai a 90 (nao subiu), reset 25 (nao reiniciou)
        cota.renovaOuPreservaContador(300, 90, 25); // L-rem = 210

        assertEquals(210, cota.contador); // max(200, 210)
    }

    @Test
    public void testRenovaOuPreservaReducaoAbaixoDoConsumidoNaoEncolhe() {
        // provedor reduz L abaixo do já consumido: contador NÃO pode encolher
        Cota cota = new Cota("Comp", DOCUMENTAL);
        cota.atualizaComHeader(300, 2, 15);
        cota.contador = 9;

        // L cai para 5, rem=0 (nao subiu), reset 12 (nao reiniciou) -> preserva
        cota.renovaOuPreservaContador(5, 0, 12); // L-rem = 5

        assertEquals(9, cota.contador); // max(9, 5) -> mantém 9
    }

    @Test
    public void testRenovaOuPreservaViradaDeJanelaRenova() {
        // rem SUBIU e reset REINICIOU: janela nova -> contador = L - rem
        Cota cota = new Cota("Comp", DOCUMENTAL);
        cota.atualizaComHeader(300, 1, 2); // fim de janela: rem=1, reset=2
        cota.contador = 280;

        // janela virou: rem 1->9 (subiu), reset 2->10 (reiniciou)
        cota.renovaOuPreservaContador(300, 9, 10); // L-rem = 291? nao: 300-9=291... ver nota

        assertEquals(291, cota.contador);
    }

    @Test
    public void testRenovaOuPreservaSubiuRemMasResetNaoReiniciouPreserva() {
        // rem subiu, mas reset NÃO reiniciou -> NÃO é virada -> preserva (evita falso positivo)
        Cota cota = new Cota("Comp", DOCUMENTAL);
        cota.atualizaComHeader(300, 3, 5);
        cota.contador = 8;

        cota.renovaOuPreservaContador(300, 7, 4); // rem subiu (3->7), reset caiu (5->4) -> preserva
        // L-rem = 293; max(8, 293) = 293

        assertEquals(293, cota.contador);
    }

    @Test
    public void testBloqueiaEDesbloqueia() {
        Cota cota = new Cota("A", DOCUMENTAL);
        cota.contador = 5;

        cota.bloqueia(42L);
        assertTrue(cota.bloqueado);
        assertEquals(42L, cota.fimBloqueio);

        cota.desbloqueia();
        assertFalse(cota.bloqueado);
        assertEquals(0L, cota.fimBloqueio);
        assertEquals(0, cota.contador); // janela renovou
    }

    @Test
    public void testCopiaDeNaoCopiaOContadorLocal() {
        Cota fonte = new Cota("Comp", DOCUMENTAL);
        fonte.atualizaComHeader(300, 150, 45);
        fonte.bloqueia(99L);
        fonte.contador = 50;

        Cota destino = new Cota("A", DOCUMENTAL);
        destino.contador = 7; // contador local do destino

        destino.copiaDe(fonte);

        assertTrue(destino.dadosReais);
        assertEquals(300, destino.limite);
        assertEquals(150, destino.restante);
        assertEquals(45, destino.reset);
        assertTrue(destino.bloqueado);
        assertEquals(99L, destino.fimBloqueio);
        assertEquals(7, destino.contador); // NÃO copiou o contador da fonte
    }
}
