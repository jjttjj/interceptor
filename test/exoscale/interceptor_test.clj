(ns exoscale.interceptor-test
  (:require [clojure.test :refer [is deftest]]
            [exoscale.interceptor :as ix]
            [exoscale.interceptor.manifold :as ixm]
            [exoscale.interceptor.core-async :as ixa]
            [exoscale.interceptor.auspex :as ixq]
            [manifold.deferred :as d]
            [qbits.auspex :as q]
            [clojure.core.async :as a]))

(def iinc {:error (fn [ctx err]
                    ctx)
           :enter (fn [ctx]
                    (update ctx :a inc))
           :leave (fn [ctx]
                    (update ctx :b inc))})

(def a {:enter #(update % :x conj :a)
        :leave #(update % :x conj :f)})

(def b {:enter #(update % :x conj :b)
        :leave #(update % :x conj :e)})

(def c {:enter #(update % :x conj :c)
        :leave #(update % :x conj :d)})

(def start-ctx {:a 0 :b 0})
(def default-result {:a 3 :b 3})
(def ex (ex-info "boom" {}))
(def throwing (fn [_] (throw ex)))

(defn clean-ctx
  [ctx]
  (dissoc ctx ::ix/queue ::ix/stack))

(deftest a-test-workflow
  (is (= default-result
         (-> (ix/execute start-ctx
                         [iinc iinc iinc])
             clean-ctx)))

  (is (= {:a 2 :b 2}
         (-> (ix/execute start-ctx
                         [iinc iinc])
             clean-ctx)))

  (is (= {}
         (-> (ix/execute {}
                         [])
             clean-ctx)))

  (is (= {}
         (-> (ix/execute {})
             clean-ctx)))

  (is (= {:a 1 :b 2}
         (-> (ix/execute start-ctx
                         [iinc
                          (assoc iinc
                                 :enter (fn [ctx] (ix/terminate ctx)))])
             clean-ctx)))

  (is (= {:a 0 :b 1}
         (-> (ix/execute start-ctx
                         [(assoc iinc
                                 :enter (fn [ctx] (ix/terminate ctx)))
                          iinc])
             clean-ctx)))

;; test flow

  (let [ctx {:x []}]
    (is (= [:a :b :c] (ix/execute ctx [a b c :x]))))

  (let [ctx {:x []}]
    (is (= [:a :b :c :d :e :f] (:x (ix/execute ctx [a b c])))))

  (let [ctx {:x []}
        b {:leave #(update % :x conj :e)
           :enter throwing
           :error (fn [ctx err]
                    ctx)}]
    ;; shortcuts to error b then stack aq
    (is (= [:a :f] (:x (ix/execute ctx [a b c])))))

  (let [ctx {:x []}
        a {:enter #(update % :x conj :a)
           :leave #(update % :x conj :f)
           :error (fn [ctx err] ctx)}
        b {:leave throwing
           :enter #(update % :x conj :b)}]
    ;; a b c then c b(boom) a(error)
    (is (= [:a :b :c :d] (:x (ix/execute ctx [a b c])))))

  (let [ctx {:x []}
        c {:enter throwing
           :leave #(update % :x conj :d)
           :error (fn [ctx err] ctx)}]
    ;; a b c(boom) then b a
    (is (= [:a :b :e :f] (:x (ix/execute ctx [a b c])))))

  (let [ctx {:x []}
        a {:enter #(throw (ex-info "boom" %))}]
    ;; a b c(boom) then b a
    (is (thrown? Exception (ix/execute ctx [a])))))

(deftest manifold-test
  (let [dinc {:enter (fn [ctx] (d/success-deferred (update ctx :a inc)))
              :leave (fn [ctx] (d/success-deferred (update ctx :b inc)))}]

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc iinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc iinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [iinc dinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc dinc])
               clean-ctx)))

    (is (= {:a 2 :b 2}
           (-> @(ix/execute start-ctx [dinc dinc])
               clean-ctx)))

    (is (= {}
           (-> @(ixm/execute {} [])
               clean-ctx))))
  (let [dinc {:enter (fn [ctx] (d/success-deferred (update ctx :a inc)))
              :leave (fn [ctx] (d/error-deferred ex))}]
    (is (thrown? Exception @(ixm/execute start-ctx
                                         [dinc]))))

  (is (= 4 @(ixm/execute {:a 1}
                         [(ix/lens inc [:a])
                          (ix/out #(d/success-deferred (-> % :a inc)) [:a])
                          (ix/out #(-> % :a inc) [:a])
                          :a])))

  (is (thrown? clojure.lang.ExceptionInfo
               @(ixm/execute {:a 1}
                             [(ix/lens inc [:a])
                              (ix/out #(d/success-deferred (-> % :a inc)) [:a])
                              (ix/out #(d/error-deferred (ex-info (str %) {})) [:a])
                              (ix/out #(-> % :a inc) [:a])
                              :a])))

  (is (thrown? clojure.lang.ExceptionInfo
               @(ixm/execute {:a 1}
                             [(ix/lens inc [:a])
                              (ix/out #(d/success-deferred (-> % :a inc)) [:a])
                              (ix/out #(throw (ex-info (str %) {})) [:a])
                              (ix/out #(-> % :a inc) [:a])
                              :a])))

  (is (= 3
         (:a @(ixm/execute {:a 1}
                           [(ix/lens inc [:a])
                            (ix/out #(d/success-deferred (-> % :a inc)) [:a])
                            {:error (fn [ctx err] ctx)}
                            (ix/out #(d/error-deferred (ex-info (str %) {})) [:a])
                            (ix/out #(-> % :a inc) [:a])]))))

  (is (= 3
         (:a @(ixm/execute {:a 1}
                           [(ix/lens inc [:a])
                            (ix/out #(d/success-deferred (-> % :a inc)) [:a])
                            {:error (fn [ctx err] ctx)}
                            (ix/out #(throw (ex-info (str %) {})) [:a])
                            (ix/out #(-> % :a inc) [:a])]))))

  (is (= 42 (ix/execute {:a 42}
                        [(ix/discard (fn [ctx] (assoc ctx :a 43)))
                         :a]))))

(deftest auspex-test
  (let [dinc {:enter (fn [ctx] (q/success-future (update ctx :a inc)))
              :leave (fn [ctx] (q/success-future (update ctx :b inc)))}]

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc iinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc iinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [iinc dinc dinc])
               clean-ctx)))

    (is (= default-result
           (-> @(ix/execute start-ctx
                            [dinc dinc dinc])
               clean-ctx)))

    (is (= {:a 2 :b 2}
           (-> @(ix/execute start-ctx [dinc dinc])
               clean-ctx)))

    (is (= {}
           (-> @(ixq/execute {} [])
               clean-ctx))))
  (let [dinc {:enter (fn [ctx] (q/success-future (update ctx :a inc)))
              :leave (fn [ctx] (q/error-future ex))}]
    (is (thrown? Exception @(ixq/execute start-ctx
                                         [dinc])))))

(deftest core-async-test
  (let [dinc {:enter (fn [ctx]
                       (doto (a/promise-chan)
                         (a/offer! (update ctx :a inc))))
              :leave (fn [ctx]
                       (doto (a/promise-chan)
                         (a/offer! (update ctx :b inc))))}]

    (is (= default-result
           (-> (a/<!! (ix/execute start-ctx
                                  [dinc dinc dinc]))
               clean-ctx)))

    (is (= default-result
           (-> (a/<!! (ixa/execute start-ctx
                                   [dinc dinc iinc]))
               clean-ctx)))

    (is (= default-result
           (-> (a/<!! (ixa/execute start-ctx
                                   [dinc iinc dinc]))
               clean-ctx)))

    (is (= default-result
           (-> (a/<!! (ixa/execute start-ctx
                                   [iinc dinc dinc]))
               clean-ctx)))

    (is (= default-result
           (-> (a/<!! (ixa/execute start-ctx
                                   [dinc dinc dinc]))
               clean-ctx)))

    (is (= {:a 2 :b 2}
           (-> (a/<!! (ixa/execute start-ctx [dinc dinc]))
               clean-ctx)))

    (is (= {}
           (-> (a/<!! (ixa/execute {} []))
               clean-ctx)))
    (let [dinc {:enter (fn [ctx] (doto (a/promise-chan (a/offer! (update ctx :a inc)))))
                :leave (fn [ctx]
                         (doto (a/promise-chan (a/offer! (ex-info "boom" {})))))}]

      (is (instance? Exception (a/<!! (ixa/execute start-ctx
                                                   [dinc]))))

      (is (instance? Exception (a/<!! (ixa/execute start-ctx
                                                   [{:enter throwing}])))))))

(deftest mixed-test
  (let [a (fn [ctx]
            (doto (a/promise-chan)
              (a/offer! (update ctx :a inc))))
        m (fn [ctx] (d/success-deferred (update ctx :a inc)))
        q (fn [ctx] (q/success-future (update ctx :a inc)))]
    (is (= 3 (:a (a/<!! (ixa/execute {:a 0} [m q a])))))
    (is (= 3 (:a @(ixm/execute {:a 0} [m q a]))))
    (is (= 3 (:a @(ixq/execute {:a 0} [m q a]))))

    ;; single type can just use execute
    (is (= 3 (:a @(ix/execute {:a 0} [m m m]))))
    (is (= 3 (:a @(ix/execute {:a 0} [q q q]))))
    (is (= 3 (:a (a/<!! (ix/execute {:a 0} [a a a])))))

    (is (thrown? Exception @(ix/execute {:a 0} [m throwing m])))
    (is (thrown? Exception @(ix/execute {:a 0} [q throwing q])))

    (is (thrown? Exception @(ixm/execute {:a 0} [throwing m m])))
    (is (thrown? Exception @(ixm/execute {:a 0} [m throwing m])))
    (is (thrown? Exception @(ixm/execute {:a 0} [m m throwing])))

    (is (thrown? Exception @(ixq/execute {:a 0} [throwing q q])))
    (is (thrown? Exception @(ixq/execute {:a 0} [q throwing q])))
    (is (thrown? Exception @(ixq/execute {:a 0} [q q throwing])))

    (is (instance? Exception (a/<!! (ixa/execute {:a 0} [a throwing a]))))
    (is (instance? Exception (a/<!! (ixa/execute {:a 0} [throwing a a]))))
    (is (instance? Exception (a/<!! (ixa/execute {:a 0} [a a throwing]))))))

(deftest out-test
  (let [counter (atom 0)
        deferred-inc (fn [arg]
                       (swap! counter inc)
                       (d/success-deferred (-> arg :a inc)))]

    (is (= 2 @(ixm/execute {:a 1}
                           [(ix/out deferred-inc [:a])
                            :a])))
    (is (= 1 @counter))))

(deftest transform-test
  (is (= {:a 1 :b 2 :c 3}
         (-> @(ix/execute {:a 1}
                          [{:enter (-> (fn [ctx] (d/success-deferred (assoc ctx :c 3)))
                                       (ix/transform (fn [ctx x] (merge ctx x {:b 2}))))}])
             (select-keys [:a :b :c]))))
  (is (= {:a 1 :b 2 :c 3}
         (-> (ix/execute {:a 1}
                         [{:enter (-> (fn [ctx] (assoc ctx :c 3))
                                      (ix/transform (fn [ctx x] (merge ctx x {:b 2}))))}])
             (select-keys [:a :b :c]))))
  (is (= {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7}
         (-> @(ix/execute {:a 1}
                          [{:enter (-> (fn [ctx] (d/success-deferred (assoc ctx :c 3)))
                                       (ix/transform (fn [ctx x] (merge ctx x {:b 2})))
                                       (ix/transform (fn [ctx x] (merge ctx x {:d 4})))
                                       (ix/transform (fn [ctx x] (d/success-deferred (merge ctx x {:e 5}))))
                                       (ix/transform (fn [ctx x] (d/success-deferred (merge ctx x {:f 6})))))}
                           ;; just to make sure we preserve chaining
                           {:enter (fn [ctx] (assoc ctx :g 7))}])
             (select-keys [:a :b :c :d :e :f :g])))))

(deftest wrap-test
  (let [f #(update % :x inc)
        m #(update % :x dec)]

    (is (= 2 (-> (ix/execute {:x 0}
                             (ix/into-stages [f f]
                                             []
                                             #(fn [s _] (ix/before-stage s m))))
                 :x))
        "does nothing")
    (is (= 0 (-> (ix/execute {:x 0}
                             (ix/into-stages [f f]
                                             [:enter]
                                             (fn [s _] (ix/before-stage s m))))
                 :x))
        "decs before incs on enter")

    (is (= 2 (-> (ix/execute {:x 0}
                             (ix/into-stages [{:enter f :leave f}
                                              {:enter f :leave f}]
                                             [:enter]
                                             (fn [s _] (ix/before-stage s m))))
                 :x))
        "decs before incs on enter, not on leave")

    (is (= 0 (-> (ix/execute {:x 0}
                             (ix/into-stages [{:enter f :leave f}
                                              {:enter f :leave f}]
                                             [:enter :leave]
                                             (fn [s _] (ix/before-stage s m))))
                 :x))
        "decs before incs on enter and on leave")

    (is (= 1 (-> (ix/execute {:x 0}
                             (ix/into-stages [{:error (fn [ctx _e]
                                                        ctx)}
                                              f
                                              f
                                              {:enter (fn [_ctx] (throw (ex-info "boom" {})))}]
                                             [:error]
                                             (fn [s _]
                                               (ix/after-stage s (fn [ctx _err] (m ctx))))))
                 :x))
        "first f incs, second too, third blows up, error stage decrs")))

(deftest chain-ops
  (let [chain [{:id ::foo :enter (fn [ctx] (ix/remove ctx #(contains? #{::bar1 ::bar2} (:id %))))}
               {:id ::bar1
                :enter (fn [_] :should-be-removed)
                :leave (fn [_] :should-be-removed)
                :error (fn [_] :should-be-removed)}
               {:id ::bar2
                :enter (fn [_] :should-be-removed)
                :leave (fn [_] :should-be-removed)
                :error (fn [_] :should-be-removed)}
               {:id ::baz :enter (fn [_] :works)}]]
    (is (= :works (ix/execute {} chain)))
    (is (= {:exoscale.interceptor/stack '(1 2 3)}
           (ix/xform-stack {:exoscale.interceptor/stack '(1 2 3)}
                           (map identity))))))
