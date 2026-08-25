#!/usr/bin/env perl
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.

use strict;
use warnings;

use Cwd qw(realpath);
use Errno qw(EEXIST ENOENT);
use Fcntl qw(:DEFAULT :flock F_SETFD);

sub fail {
    my ($message) = @_;
    die "HEV preparation lock failed: $message\n";
}

sub canonical_repo_root {
    my ($requested) = @_;
    my $canonical = realpath($requested);
    fail("trusted repository root is unavailable") unless defined $canonical;
    my @metadata = lstat($canonical);
    fail("trusted repository root is not a real directory")
        unless @metadata && -d _ && !-l _;
    return $canonical;
}

sub trusted_paths {
    my ($root, $output) = @_;
    my $expected_output = "$root/service/build/generated/hev-socks5-tunnel";
    fail("unexpected generated HEV path") unless $output eq $expected_output;
    return (
        "$root/service",
        "$root/service/build",
        "$root/service/build/generated",
        $expected_output,
    );
}

sub validate_real_directories {
    my (@paths) = @_;
    for my $path (@paths) {
        my @metadata = lstat($path);
        next if !@metadata && $! == ENOENT;
        fail("cannot inspect trusted path component: $path") unless @metadata;
        fail("trusted path component is a symlink or non-directory: $path")
            if -l _ || !-d _;
    }
}

sub ensure_parent_directories {
    my (@paths) = @_;
    pop @paths;
    for my $path (@paths) {
        my @metadata = lstat($path);
        if (!@metadata && $! == ENOENT) {
            mkdir($path, 0755) || $! == EEXIST
                or fail("cannot create trusted path component: $path");
            @metadata = lstat($path);
        }
        fail("trusted path component is a symlink or non-directory: $path")
            unless @metadata && -d _ && !-l _;
    }
}

sub validate_lock_inode {
    my ($handle, $lock_path) = @_;
    my @descriptor = stat($handle);
    my @path = lstat($lock_path);
    fail("lock path is not a real regular file")
        unless @descriptor && @path && -f _ && !-l _;
    fail("lock descriptor does not reference the expected inode")
        unless $descriptor[0] == $path[0] && $descriptor[1] == $path[1];
}

sub open_lock {
    my ($lock_path) = @_;
    my @existing = lstat($lock_path);
    if (@existing) {
        fail("lock path is a symlink or non-regular file") if -l _ || !-f _;
    } elsif ($! != ENOENT) {
        fail("cannot inspect lock path");
    }

    my $no_follow = eval { Fcntl::O_NOFOLLOW() } // 0;
    sysopen(my $lock, $lock_path, O_RDWR | O_CREAT | $no_follow, 0600)
        or fail("cannot open lock path");
    validate_lock_inode($lock, $lock_path);
    return $lock;
}

sub verify_inherited_lock {
    my ($root_arg, $output, $fd) = @_;
    fail("invalid inherited lock descriptor") unless $fd =~ /\A[0-9]+\z/;
    my $root = canonical_repo_root($root_arg);
    my @paths = trusted_paths($root, $output);
    validate_real_directories(@paths);
    my $lock_path = "$output.prepare.lock";

    open(my $lock, "+<&=$fd") or fail("inherited lock descriptor is closed");
    validate_lock_inode($lock, $lock_path);
    flock($lock, LOCK_EX | LOCK_NB)
        or fail("inherited descriptor does not hold or acquire the kernel lock");
    return;
}

sub run_locked {
    my ($root_arg, $output, $implementation, @arguments) = @_;
    my $root = canonical_repo_root($root_arg);
    my @paths = trusted_paths($root, $output);
    validate_real_directories(@paths);

    my $expected_implementation = "$root/scripts/prepare-hev-socks5-tunnel-locked.sh";
    fail("unexpected HEV preparation implementation")
        unless $implementation eq $expected_implementation;
    my @implementation_metadata = lstat($implementation);
    fail("HEV preparation implementation is not a real regular file")
        unless @implementation_metadata && -f _ && !-l _;

    ensure_parent_directories(@paths);
    validate_real_directories(@paths);
    my $lock_path = "$output.prepare.lock";
    my $lock = open_lock($lock_path);
    flock($lock, LOCK_EX) or fail("cannot acquire kernel lock");

    validate_real_directories(@paths);
    validate_lock_inode($lock, $lock_path);
    fcntl($lock, F_SETFD, 0) or fail("cannot preserve lock across exec");
    $ENV{SUBSPACE_HEV_PREPARE_LOCK_FD} = fileno($lock);

    exec('/bin/bash', $implementation, @arguments)
        or fail("cannot execute HEV preparation implementation");
}

my $mode = shift @ARGV // '';
if ($mode eq '--verify') {
    fail("usage: --verify ROOT OUTPUT FD") unless @ARGV == 3;
    verify_inherited_lock(@ARGV);
    exit 0;
}
if ($mode eq '--run') {
    fail("usage: --run ROOT OUTPUT IMPLEMENTATION [ARG ...]") unless @ARGV >= 3;
    run_locked(@ARGV);
}
fail("unknown mode");
