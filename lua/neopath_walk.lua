--[[
    neopath_walk.lua - test script for the NeoPathfinder Lua bridge.

    Walks to -300 -61 -83 and reports progress in chat.
    Load with:  /lua load neopath_walk
    Requires:   scripts/lib/pathfinder.lua
]]

local pf = require("pathfinder")

local TARGET = { x = -300, y = -61, z = -83 }

local function log(text)
    pf.log("[neopath] " .. text)
end

local ticks = 0
local unregister = nil

local function finish()
    if unregister ~= nil then
        unregister()
        unregister = nil
    end
end

pf.on_finish(function()
    local telemetry = pf.telemetry()
    local extra = ""
    if telemetry ~= nil then
        extra = string.format(" (%d nodes, %.1f ms)", telemetry.path_length, telemetry.total_ms)
    end
    log("reached the goal" .. extra)
    finish()
end)

pf.on_fail(function()
    local telemetry = pf.telemetry()
    local reason = telemetry ~= nil and telemetry.failure_reason or "unknown"
    log("path failed: " .. tostring(reason))
    finish()
end)

log(string.format("walking to %d %d %d", TARGET.x, TARGET.y, TARGET.z))
pf.go(TARGET.x, TARGET.y, TARGET.z)

unregister = pf.on_tick(function()
    ticks = ticks + 1
    if ticks % 20 == 0 and pf.is_running() then
        local telemetry = pf.telemetry()
        local detail = "-"
        if telemetry ~= nil then
            detail = string.format("%d nodes, %.1f ms", telemetry.path_length, telemetry.total_ms)
        end
        log(string.format(
            "%.1f blocks left, segment %s, last search: %s",
            pf.distance_to(TARGET.x, TARGET.y, TARGET.z),
            tostring(pf.is_pathing()),
            detail))
    end
end)

-- Stop polling if the script is unloaded while walking.
pf.on_unload(finish)

return { target = TARGET }