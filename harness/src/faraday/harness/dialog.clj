;; faraday.harness.dialog — a reusable nodecode-style modal dialog.
;; The shell owns the state in a ratom and passes it down; this namespace only
;; computes (filter, step, toggle, fold, tab) and renders. A dialog is a modal
;; overlay: title in a rounded frame, an optional tab strip, an optional filter
;; line, a navigable list, and a key footer. All keys land in the frame's
;; :on-key through a zero-size :focus-sink that holds focus, so the list is not
;; a real widget and the navigation stays in one handler, exactly like the
;; command dropdown the shell already drives.
(ns faraday.harness.dialog
  "Reusable nodecode-style modal dialog: pure navigation + a glimmer-tui view."
  (:require [clojure.string :as str]
            [faraday.harness.commands :as cmd]
            [faraday.themes :as themes]
            [glimmer-tui.keys :as k]
            [glimmer-tui.widget :as w]))

;; -- item model --------------------------------------------------------------
;; A dialog row is a map. The common keys:
;;   :label     primary text (required)
;;   :desc      dim secondary text
;;   :value     the current value, drawn after the label column
;;   :kind      :row (default) | :section | :toggle | :value | :action
;;   :checked?  for :toggle rows
;;   :expanded? for rows with :children
;;   :depth     tree indentation, in two-space steps
;;   :children  tree rows shown under this one when :expanded?
;;   :id        stable identity, used by the shell to map a filtered row back to
;;              the source it edits (slice 5)

(defn item
  "Coerce a row into the dialog item shape; a plain string is a :row."
  [x]
  (if (map? x) x {:label (str x) :kind :row}))

(defn filter-items
  "Items whose label, desc or value contains `q` (case-insensitive); a blank
  query returns every item. The shell narrows the list with this as it types."
  [items q]
  (let [items (mapv item items)
        q (str/lower-case (str/trim (str q)))]
    (if (str/blank? q)
      items
      (vec (filter (fn [it]
                     (let [hay (str/lower-case
                                (str (:label it) " " (:desc it) " " (:value it)))]
                       (str/includes? hay q)))
                   items)))))

(defn step
  "Move the selection one key step within `items`, clamped. key is one of
  :up :down :page-up :page-down :home :end."
  [items selected key]
  (let [n (count items)]
    (if (zero? n)
      0
      (let [m (dec n)]
        (case key
          :up (max 0 (dec selected))
          :down (min m (inc selected))
          :page-up (max 0 (- selected 8))
          :page-down (min m (+ selected 8))
          :home 0
          :end m
          selected)))))

(defn toggle-at
  "Flip :checked? on the item at `idx`; no-op out of range."
  [items idx]
  (if (and (<= 0 idx) (< idx (count items)))
    (update-in items [idx :checked?] not)
    items))

(defn fold-at
  "Set :expanded? on the item at `idx`: :left folds, :right unfolds."
  [items idx dir]
  (if (and (<= 0 idx) (< idx (count items)))
    (assoc-in items [idx :expanded?] (= dir :right))
    items))

(defn step-tab
  "Move between `n` tabs: :tab advances, :shift-tab retreats, clamped."
  [n tab key]
  (let [m (max 0 (dec n))]
    (case key
      :tab (min m (inc tab))
      :shift-tab (max 0 (dec tab))
      tab)))

;; -- row formatting -----------------------------------------------------------
(defn- pad [s n]
  (let [s (str s)]
    (if (< (count s) n)
      (str s (apply str (repeat (- n (count s)) " ")))
      s)))

(defn format-row
  "One row's text: indent by depth, fold marker or checkmark, the label padded
  to `name-width`, then the value. Pure so tests can pin columns."
  [it name-width]
  (let [it (item it)
        indent (apply str (repeat (* 2 (or (:depth it) 0)) " "))
        mark (cond
               (seq (:children it)) (if (:expanded? it) "▾ " "▸ ")
               (= :toggle (:kind it)) (if (:checked? it) "[x] " "[ ] ")
               :else "")
        name (str indent mark (:label it))
        value (str (or (:value it) ""))
        line (if (str/blank? value)
               name
               (str (pad name name-width) "  " value))]
    (if (str/blank? (:desc it))
      line
      (str line "  " (:desc it)))))

(defn- row-name
  "The name column of a row: indent + fold/check mark + label, without value or
  desc. format-rows pads to the widest name, so a long :value never inflates the
  name column and pushes the value past the right edge of the dialog."
  [it]
  (let [it (item it)
        indent (apply str (repeat (* 2 (or (:depth it) 0)) " "))
        mark (cond
               (seq (:children it)) (if (:expanded? it) "▾ " "▸ ")
               (= :toggle (:kind it)) (if (:checked? it) "[x] " "[ ] ")
               :else "")]
    (str indent mark (:label it))))

(defn- spaces [n] (apply str (repeat (max 0 (long n)) " ")))

(defn- trunc
  "Clip `s` to at most `n` chars, appending … when it was longer."
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- (long n) 1))) "…")
      s)))

(defn- word-wrap
  "Wrap `s` into lines no wider than `width` chars, preserving newlines and
  hard-breaking words longer than `width`. Mirrors chat/word-wrap."
  [s width]
  (let [w (max 1 (long width))]
    (vec
     (mapcat
      (fn [line]
        (if (<= (count line) w)
          [line]
          (loop [rem (str/triml line) acc []]
            (let [n (count rem)]
              (if (<= n w)
                (conj acc rem)
                (let [chunk (subs rem 0 w)
                      cut (str/last-index-of chunk " ")]
                  (if (and cut (pos? cut))
                    (recur (subs rem (inc cut)) (conj acc (subs rem 0 cut)))
                    (recur (subs rem w) (conj acc chunk)))))))))
      (str/split s #"\n" -1)))))

(defn- format-row-wrapped
  "A row's text with value+desc wrapped to `colw` chars and capped at
  `max-lines` lines. Continuation lines indent under the value column, and the
  last line gains … when the tail is clipped."
  [it name-w colw max-lines]
  (let [it (item it)
        name (trunc (row-name it) name-w)
        value (str (or (:value it) ""))
        desc (str (or (:desc it) ""))
        tail (if (str/blank? desc) value (str value "  " desc))]
    (if (str/blank? tail)
      name
      (let [tail-lines (word-wrap tail colw)
            prefix (str (pad name name-w) "  ")
            cont (spaces (+ name-w 2))
            lines (into [(str prefix (first tail-lines))]
                        (map #(str cont %) (rest tail-lines)))]
        (if (> (count lines) max-lines)
          (let [keep (subvec lines 0 max-lines)
                last-idx (dec max-lines)
                last-line (keep last-idx)
                trimmed (subs last-line 0 (max 0 (- (count last-line) 1)))]
            (str/join "\n" (assoc keep last-idx (str trimmed "…"))))
          (str/join "\n" lines))))))

(defn format-rows
  "Format every row for display. `width` is the dialog's content width: the name
  column is padded to the widest name (capped at half the width so a long key
  can't collapse the value column), and each value+desc tail wraps to the
  remaining columns, capped at two lines with a trailing …. Returns
  [{:label line} …] where line may be multi-line; the dialog paints these as
  labels itself so selection stays fully ours."
  [items width]
  (let [items (mapv item items)
        inner (max 16 (- (long width) 8))
        name-w (min (quot inner 2)
                    (reduce max 0 (map (comp count row-name) items)))
        colw (max 8 (- inner name-w 2))]
    (mapv (fn [it] {:label (format-row-wrapped it name-w colw 2)}) items)))

;; -- key mapping --------------------------------------------------------------
(defn key-action
  "Map a decoded key event to a dialog action [kw arg], or nil when the dialog
  declines the key (esc falls through to the overlay's :on-close). Pure."
  [ev tabs?]
  (cond
    (k/match? ev "up") [:step :up]
    (k/match? ev "down") [:step :down]
    (k/match? ev "pgup") [:step :page-up]
    (k/match? ev "pgdn") [:step :page-down]
    (k/match? ev "home") [:step :home]
    (k/match? ev "end") [:step :end]
    (k/match? ev "space") [:toggle]
    (k/match? ev "left") [:fold :left]
    (k/match? ev "right") [:fold :right]
    (and tabs? (k/match? ev "tab")) [:tab :tab]
    (and tabs? (k/match? ev "back-tab")) [:tab :shift-tab]
    (k/match? ev "backspace") [:filter-backspace]
    (k/match? ev "enter") [:activate]
    (k/printable? ev) [:filter-char (k/char-of ev)]
    :else nil))

(defn apply-action
  "One pure step of the dialog model for `action`. :activate is returned
  unchanged — the shell maps it to behaviour via its own callback, because it is
  the single place ids become actions."
  [dlg action]
  (let [[kw arg] action]
    (case kw
      :step (assoc dlg :selected (step (:items dlg) (:selected dlg) arg))
      :toggle (assoc dlg :items (toggle-at (:items dlg) (:selected dlg)))
      :fold (assoc dlg :items (fold-at (:items dlg) (:selected dlg) arg))
      :tab (assoc dlg :tab (step-tab (count (:tabs dlg)) (or (:tab dlg) 0) arg))
      :filter-char (-> dlg
                       (update :filter str (str arg))
                       (assoc :selected 0))
      :filter-backspace (-> dlg
                            (update :filter #(subs % 0 (max 0 (dec (count %)))))
                            (assoc :selected 0))
      dlg)))

;; -- the focus sink -----------------------------------------------------------
(def ^:private focus-sink-spec
  "An invisible, zero-size widget that takes keyboard focus. A dialog needs one
  so the focus path exists — keys the sink declines bubble from it to the
  dialog's :on-key. It paints nothing and occupies no space."
  {:container :none
   :measure (fn [_ _] {:w 0 :h 0})
   :min-size (fn [_ _ _] {:w 0 :h 0})
   :focusable? (fn [_] true)
   :paint (fn [_ _ _ _ _] nil)})

(defn install! []
  (w/register-widget! :focus-sink focus-sink-spec)
  nil)

;; -- the view ----------------------------------------------------------------
  (defn- row-hiccup [line selected?]
  [:hbox {:hexpand true :spacing 0 :padding [0 1]}
   [:label {:label " "
             :bg (if selected? (themes/color :selection) (themes/color :canvas))}]
   [:label {:label line
            :color (if selected? (themes/color :accent) (themes/color :body))
            :bold selected?
            :bg (themes/color :canvas)}]])

(defn dialog-view
  "Pure hiccup for a nodecode-style modal dialog. `st` carries :title :items
  :selected :filter :filter? :tabs :tab :footer :on-close :on-key :on-activate
  :max-rows :width. Rows are filtered by :filter, then windowed so the
  selection stays inside the dialog (cmd/visible-window)."
  [st]
  (let [{:keys [title items selected filter filter? tabs tab footer
                on-close on-key max-rows width]} st
        items (filter-items items filter)
        rows (format-rows items (or width 64))
        m (or max-rows 10)
        [start n] (cmd/visible-window rows selected m)
        shown (subvec rows start (+ start n))]
    [:overlay {:anchor :center :modal true :bg (themes/color :canvas)
               :width-request (or width 64)
               :on-close on-close}
     [:frame {:label (str " " (or title "") " ") :border :rounded
              :color (themes/color :muted) :on-key on-key}
      [:vbox {:spacing 0 :padding [0 1] :hexpand true}
       [:focus-sink {:autofocus true}]
       (when (seq tabs)
         [:hbox {:spacing 2 :padding [0 0 0 0]}
          (map-indexed (fn [i t]
                         [:label {:label (if (= i tab) (str "[" t "]") (str " " t " "))
                                  :color (if (= i tab) (themes/color :accent) (themes/color :muted))
                                  :bold (= i tab)}])
                       tabs)])
       (when filter?
         [:label {:label (if (str/blank? filter) "/" (str "/" filter))
                  :color (themes/color :body) :bold true}])
       (if (seq shown)
         (into [:vbox {:spacing 0}]
               (map-indexed (fn [i line]
                              (row-hiccup (:label line) (= (+ start i) selected)))
                            shown))
         [:label {:label "  no matching rows" :color (themes/color :muted) :dim true}])
       (when footer
         [:label {:label footer :color (themes/color :muted) :dim true}])]]]))

(defn editor-view
  "Pure hiccup for the inline value editor: a small modal over the dialog with a
  text entry pre-filled with the current value (blank for masked secrets) and a
  key footer. :on-change updates the draft, :on-activate commits, :on-close
  cancels."
  [{:keys [label value on-change on-activate on-close]}]
  [:overlay {:anchor :center :modal true :bg (themes/color :canvas)
             :width-request 58 :on-close on-close}
   [:frame {:label (str " " (or label "") " ") :border :rounded :color (themes/color :muted)}
    [:vbox {:spacing 0 :padding [0 1] :hexpand true}
     [:entry {:text value :autofocus true :hexpand true
              :color (themes/color :sheet) :bg (themes/color :body)
              :on-change on-change :on-activate on-activate}]
     [:label {:label "enter save · esc cancel" :color (themes/color :muted) :dim true}]]]])
