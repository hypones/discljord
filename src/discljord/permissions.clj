(ns discljord.permissions
  "Functions for determining users' permissions.")

(def permissions-bit
  "Map from permission names to the binary flag representation of it."
  {:create-instant-invite 0x1
   :kick-members 0x2
   :ban-members 0x4
   :administrator 0x8
   :manage-channels 0x10
   :manage-guild 0x20
   :add-reactions 0x40
   :view-audit-log 0x80
   :priority-speaker 0x100
   :stream 0x200
   :view-channel 0x400
   :send-messages 0x800
   :send-tts-messages 0x1000
   :manage-messages 0x2000
   :embed-links 0x4000
   :attach-files 0x8000
   :read-message-history 0x10000
   :mention-everyone 0x20000
   :use-external-emojis 0x40000
   :view-guild-insights 0x80000
   :connect 0x100000
   :speak 0x200000
   :mute-members 0x400000
   :deafen-members 0x800000
   :move-members 0x1000000
   :use-vad 0x2000000
   :change-nickname 0x4000000
   :manage-nicknames 0x8000000
   :manage-roles 0x10000000
   :manage-webooks 0x20000000
   :manage-emojis 0x40000000})

(defn has-permission-flag?
  "Returns if the given permission integer includes a permission flag.

  `perm` is a keyword from the keys of [[permissions-int]]."
  [perm perms-int]
  (when perms-int
    (when-let [bit (or (permissions-bit perm)
                       perm)]
      (not (zero? (bit-and bit perms-int))))))

(defn has-permission-flags?
  "Returns if the given permission integer includes all the given permission flags.

  `perm` is a keyword from the keys of [[permissions-int]]."
  [perms perms-int]
  (every? #(has-permission-flag? % perms-int) perms))

(defn- override
  "Integrates the overrides into the permissions int."
  [perms-int overrides]
  (let [allow (or (when (seq overrides)
                    (reduce bit-or 0 (map :allow overrides)))
                  0)
        deny (or (when (seq overrides)
                   (reduce bit-or 0 (map :deny overrides)))
                 0)]
    (bit-or
     (bit-and
      perms-int
      (bit-not deny))
     allow)))

(defn permission-int
  "Constructs a permissions integer from role permissions integers and overrides.

  `everyone` is a permissions integer.
  `roles` is a sequence of permissions integers.

  Each of the override objects is an [overwrite object](https://discord.com/developers/docs/resources/channel#overwrite-object)
  from their respective items (everyone, roles, and member overrides).

  `roles-overrides` is a sequence of these objects."
  ([everyone roles]
   (let [perms-int (reduce bit-or 0 (conj roles everyone))]
     (if (has-permission-flag? :administrator perms-int)
       0xFFFFFFFF
       perms-int)))
  ([everyone roles everyone-override roles-overrides user-override]
   (let [base-perms-int (permission-int everyone roles)]
     (if (has-permission-flag? :administrator base-perms-int)
       0xFFFFFFFF
       (-> base-perms-int
           (override (when everyone-override
                       [everyone-override]))
           (override roles-overrides)
           (override (when user-override
                       [user-override])))))))

(defn user-roles
  "Returns a sequence of permissions integers for a user's roles.

  `guild` is a guild object like those returned from
  [[discljord.events.state/prepare-guild]] or stored in the state atom from
  [[discljord.events.state/caching-middleware]].

  This is primarily used to construct calls to [[permission-int]]."
  [guild user-id-or-member]
  (map :permissions (vals (select-keys (:roles guild)
                                       (:roles (if (map? user-id-or-member)
                                                 user-id-or-member
                                                 ((:members guild) user-id-or-member)))))))

(defn- permissions-and-overrides
  "Constructs a vector with the arguments needed for a call to [[permission-int]]."
  [guild user-id-or-member channel-id]
  (let [everyone (:permissions ((:roles guild) (:id guild)))
        roles (user-roles guild user-id-or-member)
        {:keys [permission-overwrites]} ((:channels guild) channel-id)
        {role-overrides "role" member-overrides "member"} (group-by :type permission-overwrites)
        member (if (map? user-id-or-member)
                 user-id-or-member
                 ((:members guild) user-id-or-member))
        everyone-override (first (filter (comp #{(:id guild)} :id) role-overrides))
        role-overrides (filter (comp (set (:roles member)) :id) role-overrides)
        user-id (if (map? user-id-or-member)
                  (let [user (:user user-id-or-member)]
                    (if (map? user)
                      (:id user)
                      user))
                  user-id-or-member)
        member-override (first (filter (comp #{user-id} :id) member-overrides))]
    [everyone roles everyone-override role-overrides member-override]))

(defn has-permission?
  "Returns if the given user has a permission.

  `perm` is a keyword from the keys of [[permissions-bit]].
  `everyone` is a permissions integer.
  `roles` is a sequence of permissions integers.
  `guild` is a guild object like those from [[discljord.events.state/prepare-guild]].

  If not passed a guild object, the calling code will have to construct the list
  of overrides and role permissions ints itself. See [[permission-int]] for
  documentation of override objects."
  {:arglists '([perm everyone roles] [perm guild user-id-or-member] [perm guild user-id-or-member channel-id]
               [perm everyone roles everyone-overrides roles-overrides user-overrides])}
  ([perm everyone-or-guild roles-or-user-id-or-member]
   (if (map? everyone-or-guild)
     (has-permission-flag? perm (permission-int (:permissions ((:roles everyone-or-guild) (:id everyone-or-guild)))
                                                (user-roles everyone-or-guild roles-or-user-id-or-member)))
     (has-permission-flag? perm (permission-int everyone-or-guild roles-or-user-id-or-member))))
  ([perm guild user-id-or-member channel-id]
   (has-permission-flag?
    perm
    (apply permission-int (permissions-and-overrides guild user-id-or-member channel-id))))
  ([perm everyone roles everyone-override roles-overrides user-override]
   (has-permission-flag?
    perm
    (permission-int everyone roles everyone-override roles-overrides user-override))))

(defn has-permissions?
  "Returns if the given user has each of a sequence of permissions.

  `perms` is a sequence of keywords from the keys of [[permissions-bit]].
  `everyone` is a permissions integer.
  `roles` is a sequence of permissions integers.
  `guild` is a guild object like those from [[discljord.events.state/prepare-guild]].

  If not passed a guild object, the calling code will have to construct the list
  of overrides and role permissions ints itself. See [[permission-int]] for
  documentation of override objects."
  {:arglists '([perms everyone roles] [perms guild user-id-or-member] [perms guild user-id-or-member channel-id]
               [perms everyone roles everyone-overrides roles-overrides user-overrides])}
  ([perms everyone-or-guild roles-or-user-id-or-member]
   (if (map? everyone-or-guild)
     (has-permission-flags?
      perms
      (permission-int (:permissions ((:roles everyone-or-guild) (:id everyone-or-guild)))
                      (user-roles everyone-or-guild roles-or-user-id-or-member)))
     (has-permission-flags? perms (permission-int everyone-or-guild roles-or-user-id-or-member))))
  ([perms guild user-id-or-member channel-id]
   (has-permission-flags?
    perms
    (apply permission-int (permissions-and-overrides guild user-id-or-member channel-id))))
  ([perms everyone roles everyone-override roles-overrides user-override]
   (has-permission-flags?
    perms
    (permission-int everyone roles everyone-override roles-overrides user-override))))
