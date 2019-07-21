clj-runtime-completion.jar: deps.edn src/**/*
	clojure -A:pack \
		mach.pack.alpha.skinny \
		--no-libs \
		--project-path clj-runtime-completion.jar

pom.xml: deps.edn
	clojure -Spom

all: pom.xml clj-runtime-completion.jar