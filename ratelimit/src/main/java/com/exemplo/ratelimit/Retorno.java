package com.exemplo.ratelimit;

/** O que o proxy devolve ao sistema interno (resultado da ordem canônica). */
public enum Retorno {
    SUCESSO_200,      // 200 + corpo da API
    ERRO_REPASSADO,   // erro do provedor repassado (ex. 500)
    RECUSA_GENERICA   // limite/bloqueio: erro genérico ao interno (o proxy devolve 429 ao cliente)
}
