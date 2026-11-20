--[[
    pathfinder.lua - NeoPathfinder bridge for NeoScripts.

    No Java glue code: everything goes through LuaJava reflection
    (`luajava.bindClass` / `luajava.newInstance` / `luajava.createProxy`),
    which NeoScripts already provides (its `luajava` table is a
    Minecraft-aware LuajavaLib, so named/intermediary classes resolve).

    Install: copy this file to
        <instance>/config/neoscripts/scripts/lib/pathfinder.lua
    Use:      local pf = require("pathfinder")

    The raw API is always reachable: `pf.api` is the bound
    com.nekiplay.neopathfinder.api.PathAPI class, `pf.java.bind(name)`
    binds any other class.
]]

local M = {}

local ok, err = pcall(function()
    M.bind = function(name)
        return luajava.bindClass(name)
    end

    local unpack_fn = table.unpack or unpack

    M.new = function(name, ...)
        local args = { ... }
        return luajava.newInstance(name, unpack_fn(args))
    end

    M.api = M.bind("com.nekiplay.neopathfinder.api.PathAPI")
    -- Not fatal: positions can also come from the NeoScripts `player` module.
    pcall(function()
        M.minecraft = M.bind("net.minecraft.client.Minecraft"):getInstance()
    end)
end)

if not ok then
    error("pathfinder.lua: cannot bind the NeoPathfinder API (" .. tostring(err) .. ")\n" ..
        "Is the NeoPathfinder mod installed and the class name unchanged?")
end

local API = M.api

local function player()
    if M.minecraft == nil then
        return nil
    end
    return M.minecraft.player
end

local playerModule = nil

local function modPlayer()
    if playerModule == nil then
        local loaded, mod = pcall(require, "player")
        playerModule = loaded and mod or false
    end
    return playerModule or nil
end

local function blockpos(x, y, z)
    return M.new("net.minecraft.core.BlockPos", math.floor(x), math.floor(y), math.floor(z))
end

M.blockpos = blockpos

local function to_table(pos)
    if pos == nil then
        return nil
    end
    return { x = pos:getX(), y = pos:getY(), z = pos:getZ() }
end

M.to_table = to_table

-- logging ---------------------------------------------------------------

local function say(text)
    local p = player()
    if p ~= nil then
        local sent = pcall(function()
            p.addMessage(tostring(text))
        end)
        if sent then
            return
        end
    end
    local mod = modPlayer()
    if mod ~= nil then
        local sent = pcall(mod.addMessage, tostring(text))
        if sent then
            return
        end
    end
    print(text)
end

M.log = say

-- movement --------------------------------------------------------------

--- Stops the current path, clears the queue and walks to x, y, z (block coordinates).
function M.go(x, y, z)
    API:goTo(blockpos(x, y, z))
end

--- Appends a waypoint to the queue (the path continues from the previous point).
function M.add_waypoint(x, y, z)
    API:addWaypoint(blockpos(x, y, z))
end

--- Starts following the queued waypoints.
function M.start()
    API:start()
end

--- Stops following and clears the queue.
function M.stop(reason)
    if reason ~= nil then
        API:stop(tostring(reason))
    else
        API:stop()
    end
end

--- Clears the waypoint queue without stopping the current segment.
function M.clear()
    API:clearWaypoints()
end

-- state ----------------------------------------------------------------

function M.is_running()
    return API:isRunning()
end

function M.is_pathing()
    return API:isPathing()
end

function M.has_waypoints()
    return API:hasWaypoints()
end

--- {x, y, z} of the player's feet, or nil when not in a world.
function M.pos()
    local p = player()
    if p ~= nil then
        return { x = p:getX(), y = p:getY(), z = p:getZ() }
    end
    local mod = modPlayer()
    if mod ~= nil then
        local pos = mod.getPos()
        return { x = pos.x, y = pos.y, z = pos.z }
    end
    return nil
end

--- BlockPos of the block the player stands in.
function M.block_pos()
    local p = M.pos()
    if p == nil then
        return nil
    end
    return blockpos(p.x, p.y, p.z)
end

--- The block the executor is walking toward, or nil.
function M.target()
    return to_table(API:getCurrentTarget())
end

--- The block the executor started the current segment from, or nil.
function M.start_point()
    return to_table(API:getCurrentPosition())
end

--- Queued destinations as an array of {x, y, z}.
function M.waypoints()
    local list = API:getWaypoints()
    local size = list:size()
    local out = {}
    for i = 0, size - 1 do
        out[i + 1] = to_table(list:get(i))
    end
    return out
end

--- Telemetry of the last search, or nil when nothing ran yet.
function M.telemetry()
    local t = API:getLastTelemetry()
    if t == nil then
        return nil
    end
    return {
        success = t:success(),
        failure_reason = t:failureReason(),
        search_ms = t:searchMs(),
        smoothing_ms = t:smoothingMs(),
        total_ms = t:getTotalMs(),
        expanded_nodes = t:expandedNodes(),
        open_set_peak = t:openSetPeak(),
        iterations = t:iterations(),
        path_length = t:pathLength(),
        smoothed_path_length = t:smoothedPathLength(),
        direct_walk = t:directWalk(),
    }
end

--- Horizontal distance from the player to x, z.
function M.distance_to(x, y, z)
    local p = M.pos()
    if p == nil then
        return math.huge
    end
    return math.sqrt((p.x - x) ^ 2 + (p.z - z) ^ 2)
end

-- behaviour ------------------------------------------------------------

--- Upper bound on A* loop passes per search (default 2,000,000).
function M.set_search_limit(iterations)
    API:setSearchLimit(math.floor(iterations))
end

function M.search_limit()
    return API:getSearchLimit()
end

function M.set_sprint(allowed)
    API:setSprintAllowed(allowed and true or false)
end

--- fixed=true -> constant rotation time, otherwise distance-based easing.
function M.set_rotation(fixed, time_ms, distance_factor)
    API:setRotationProfile(fixed and true or false, math.floor(time_ms or 500), distance_factor or 2.0)
end

-- callbacks ------------------------------------------------------------

local function runnable(fn)
    return luajava.createProxy("java.lang.Runnable", { run = fn })
end

--- Called once when the whole queued path is completed.
function M.on_finish(fn)
    API:onFinish(runnable(fn))
end

--- Called once when the path fails (no path, stuck, timeout).
function M.on_fail(fn)
    API:onFail(runnable(fn))
end

--- Runs fn on every client tick; returns an unregister function.
function M.on_tick(fn)
    local register = registerClientTick
    local unregister = unregisterClientTick
    if register == nil then
        error("pathfinder.lua: registerClientTick is not available")
    end
    register(fn)
    return function()
        if unregister ~= nil then
            unregister(fn)
        end
    end
end

--- Runs fn when the script is unloaded; returns an unregister function.
function M.on_unload(fn)
    local register = registerUnloadCallback
    local unregister = unregisterUnloadCallback
    if register == nil then
        return function() end
    end
    register(fn)
    return function()
        if unregister ~= nil then
            unregister(fn)
        end
    end
end

return M