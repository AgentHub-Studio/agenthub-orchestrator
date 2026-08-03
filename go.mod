module github.com/AgentHub-Studio/agenthub-orchestrator

go 1.25.0

require (
	github.com/AgentHub-Studio/agenthub-go-commons v0.0.0-00010101000000-000000000000
	github.com/expr-lang/expr v1.16.9
	github.com/go-chi/chi/v5 v5.2.4
	github.com/google/uuid v1.6.0
	github.com/jackc/pgx/v5 v5.9.2
	github.com/rabbitmq/amqp091-go v1.10.0
	github.com/stretchr/testify v1.11.1
	golang.org/x/sync v0.20.0
)

replace github.com/AgentHub-Studio/agenthub-go-commons => /agenthub-go-commons

require (
	github.com/davecgh/go-spew v1.1.2-0.20180830191138-d8f796af33cc // indirect
	github.com/golang-jwt/jwt/v5 v5.2.2 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	github.com/kr/text v0.2.0 // indirect
	github.com/pmezard/go-difflib v1.0.1-0.20181226105442-5d4384ee4fb2 // indirect
	github.com/rogpeppe/go-internal v1.14.1 // indirect
	golang.org/x/text v0.37.0 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
)
