Os testes rodam com algum perfil Spring ativo (tipo @ActiveProfiles("test")), ou usam o application.yml base/default? E existe algum application-test.yml ou application-test.properties?
O que o hazelcast.members resolve durante os testes — os testes definem essa propriedade em algum lugar (@TestPropertySource), ou herdam o default do HazelcastConfig (localhost:5701,localhost:5702)?
